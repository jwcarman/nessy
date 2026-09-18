/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Gathering and judging, kept apart.
 *
 * <p>An enricher never decides; it makes something available. That separation is what lets a risk
 * score, a resolved principal and a quota check be added independently of each other and of
 * whatever eventually weighs them -- and what lets one approver serve agents whose questions are
 * enriched differently.
 *
 * <p>The second reader matters as much as the first. A deferred question is read by a person hours
 * later, so what an enricher writes is evidence for a human as much as input for a rule.
 */
class ApprovalEnrichmentTest {

  private EngineFixture engine;

  /**
   * One engine per test, and each built around the model that test needs.
   *
   * <p>The provider is a factory-level setting -- one model serves every harness an engine hands
   * out -- so a class that varies what the model asks for varies the engine, not the harness.
   */
  private void running(InferenceProvider model) {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  /** The convention a module publishes, so both sides spell it once. */
  private static final String RISK = "risk.score";

  private static final String PRINCIPAL = "who.principal";

  record Wipe(String target) {}

  private final ConcurrentLinkedQueue<String> ran = new ConcurrentLinkedQueue<>();

  private Tool<Wipe> dangerous() {
    return new Tool<>() {
      @Override
      public Class<Wipe> inputType() {
        return Wipe.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("wipe");
      }

      @Override
      public String description() {
        return "deletes everything under a path";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Wipe> request) {
        ran.add(request.input().target());
        return Awaited.ready(ToolResult.ok(new Block.Text("wiped")));
      }
    };
  }

  private String agentStateOf(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT state_type FROM nessy_agent_state WHERE agent_id = ?")
        .params(agentId.value())
        .query(String.class)
        .single();
  }

  private static InferenceProvider asksToWipe(String target) {
    return (request, _) ->
        request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
            ? new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done"))
            : new InferenceResult.Actions(
                List.of(new Block.ToolCall("call_1", "wipe", "{\"target\":\"" + target + "\"}")));
  }

  /**
   * Two enrichers, neither of which decides anything, and an approver that decides using both.
   *
   * <p>Neither enricher knows the other exists, and neither knows what the approver will make of
   * what it wrote. That is the composition the split buys: adding a third is a line of
   * configuration, not a change to the thing that judges.
   */
  @Test
  void severalEnrichersGatherAndTheApproverJudges() {
    running(asksToWipe("/prod/data"));
    AgentType type = new AgentType("enriched-denied");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<ApprovalRequest> seen = new ConcurrentLinkedQueue<>();

    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            dangerous(),
                            t ->
                                t.action(wipe -> "delete everything under " + wipe.target())
                                    // Gathers. Says nothing about whether this should be allowed.
                                    .enrich(
                                        request ->
                                            request.fact(
                                                RISK,
                                                JsonNodeFactory.instance.numberNode(
                                                    request.arguments().contains("/prod")
                                                        ? 90
                                                        : 5)))
                                    .enrich(request -> request.fact(PRINCIPAL, "svc-deployer"))
                                    // Judges, using what it was handed and nothing it gathered
                                    // itself.
                                    .approver(
                                        request -> {
                                          seen.add(request);
                                          int risk =
                                              request.fact(RISK).map(JsonNode::asInt).orElse(100);
                                          return Awaited.ready(
                                              risk >= 50
                                                  ? ApprovalResult.denied(
                                                      "risk " + risk + " is too high")
                                                  : ApprovalResult.approved());
                                        }))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "clean up /prod/data");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(agentId)).isEqualTo("Idle"));

    assertThat(seen)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.fact(RISK)).get().extracting(JsonNode::asInt).isEqualTo(90);
              assertThat(request.fact(PRINCIPAL))
                  .get()
                  .extracting(JsonNode::asString)
                  .isEqualTo("svc-deployer");
              assertThat(request.action())
                  .as("the sentence is there to be read before anything is added to it")
                  .isEqualTo("delete everything under /prod/data");
            });
    assertThat(ran).as("denied on what the enrichers found").isEmpty();
    assertThat(engine.history().entriesFrom(type, agentId, 0).get(2))
        .isEqualTo(
            new HistoryEntry.ToolDenied(
                new Seq(3),
                new TurnId(1),
                new CallId("call_1"),
                "risk 90 is too high",
                java.util.Optional.empty()));
  }

  /** The same approver, the same enrichers, a different call -- and the other answer. */
  @Test
  void theSameGatherersAndJudgeAllowAHarmlessCall() {
    running(asksToWipe("/tmp/scratch"));
    AgentType type = new AgentType("enriched-allowed");
    AgentId agentId = new AgentId(UUID.randomUUID());

    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            dangerous(),
                            t ->
                                t.action(wipe -> "delete everything under " + wipe.target())
                                    .enrich(
                                        request ->
                                            request.fact(
                                                RISK,
                                                JsonNodeFactory.instance.numberNode(
                                                    request.arguments().contains("/prod")
                                                        ? 90
                                                        : 5)))
                                    .approver(
                                        request ->
                                            Awaited.ready(
                                                request.fact(RISK).map(JsonNode::asInt).orElse(100)
                                                        >= 50
                                                    ? ApprovalResult.denied("too risky")
                                                    : ApprovalResult.approved())))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "clean up /tmp/scratch");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(agentId)).isEqualTo("Idle"));

    assertThat(ran).containsExactly("/tmp/scratch");
  }

  @Test
  void enrichersRunInTheOrderTheyWereAdded() {
    running(asksToWipe("/tmp/x"));
    AgentType type = new AgentType("enriched-ordered");
    AgentId agentId = new AgentId(UUID.randomUUID());
    AtomicInteger next = new AtomicInteger();
    ConcurrentLinkedQueue<ApprovalRequest> seen = new ConcurrentLinkedQueue<>();

    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            dangerous(),
                            t ->
                                t.enrich(
                                        request ->
                                            request.fact(
                                                "first", String.valueOf(next.getAndIncrement())))
                                    .enrich(
                                        request ->
                                            request.fact(
                                                "second", String.valueOf(next.getAndIncrement())))
                                    .approver(
                                        request -> {
                                          seen.add(request);
                                          return Awaited.ready(ApprovalResult.denied("enough"));
                                        }))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "go");
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(seen).isNotEmpty());

    assertThat(seen.peek().fact("first")).get().extracting(JsonNode::asString).isEqualTo("0");
    assertThat(seen.peek().fact("second")).get().extracting(JsonNode::asString).isEqualTo("1");
  }

  /**
   * A gatherer that broke is not a gatherer that found nothing.
   *
   * <p>Quietly contributing nothing is what turns a broken risk service into a silent approval: the
   * approver reads no risk fact, falls through to its default, and lets the call run. So a throw
   * discharges the call as one that could not be authorised, and the tool never runs.
   */
  @Test
  void anEnricherThatThrowsStopsTheCallRatherThanApprovingIt() {
    running(asksToWipe("/prod/data"));
    AgentType type = new AgentType("enriched-broken");
    AgentId agentId = new AgentId(UUID.randomUUID());

    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            dangerous(),
                            t ->
                                t.enrich(
                                        _ -> {
                                          throw new IllegalStateException("risk service is down");
                                        })
                                    // Would say yes to anything, which is the point: the enricher
                                    // is what
                                    // stops this, and a silent failure would leave the yes
                                    // standing.
                                    .approver(Approver.allow()))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "clean up /prod/data");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(agentId)).isEqualTo("Idle"));

    assertThat(ran).as("a broken gatherer must not become an approval").isEmpty();
    assertThat(engine.history().entriesFrom(type, agentId, 0))
        .noneMatch(HistoryEntry.ToolApproved.class::isInstance);
  }

  /** Nothing gathers by default, and a question with no facts is an ordinary one. */
  @Test
  void aQuestionWithNoEnrichersCarriesNoFacts() {
    running(asksToWipe("/tmp/x"));
    AgentType type = new AgentType("enriched-none");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<ApprovalRequest> seen = new ConcurrentLinkedQueue<>();

    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            dangerous(),
                            t ->
                                t.approver(
                                    request -> {
                                      seen.add(request);
                                      return Awaited.ready(ApprovalResult.approved());
                                    }))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "go");
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(seen).isNotEmpty());

    assertThat(seen.peek().facts().isEmpty()).isTrue();
    assertThat(seen.peek().fact(RISK)).isEmpty();
  }
}
