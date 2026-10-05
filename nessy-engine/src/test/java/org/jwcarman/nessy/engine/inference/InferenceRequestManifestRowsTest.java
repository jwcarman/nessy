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
package org.jwcarman.nessy.engine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.event.InferenceRequestManifest;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.ToolOffer;

/**
 * What the manifest costs in a real database: a request that is made of the same things as the one
 * before it stores nothing new, because a payload's reference is a hash of its content and putting
 * it again is a no-op.
 */
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InferenceRequestManifestRowsTest {

  private static final AgentType TYPE = new AgentType("manifest");
  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private final EngineFixture engine =
      new EngineFixture(
          (request, narrator) -> {
            if (request.context().activeTurn().exchanges().isEmpty()) {
              return new InferenceResult.Actions(
                  List.of(new Block.ToolCall("c1", "lookup", "{\"q\":\"x\"}")),
                  Usage.unreported("a-model"));
            }
            return new InferenceResult.Answer(
                List.of(new Block.Text("done")), Usage.unreported("a-model"));
          });

  record Query(String q) {}

  private static Tool<Query> lookup() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("1412 metres")));
      }
    };
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  private long rows(AgentId agent) {
    return engine
        .jdbc()
        .sql("SELECT count(*) FROM nessy_payload WHERE agent_id = ?")
        .param(agent.value())
        .query(Long.class)
        .single();
  }

  /**
   * One turn whose model is called twice: it asks for the tool, then answers. {@code ambient} is
   * asked once on the way into each call.
   */
  private AgentId oneTurnTwoCalls(AmbientSource ambient) {
    AgentId agent = AgentId.random();
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                TYPE,
                String.class,
                c ->
                    c.systemPrompt("You are terse.")
                        .tool(lookup())
                        .ambient(ambient)
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
    harness.tell(agent, "look it up");
    await()
        .atMost(PATIENCE)
        .untilAsserted(
            () ->
                assertThat(engine.history().forAgent(TYPE, agent).completedAfter(Optional.empty()))
                    .hasSize(1));
    return agent;
  }

  @Test
  void two_calls_with_nothing_changed_store_nothing_new() {
    AmbientSource steady = AmbientSource.constant(Ambient.text("clock", "noon"));

    AgentId agent = oneTurnTwoCalls(steady);

    // Content of the turn itself: the input, the model's request for the tool, the tool's result
    // and the answer = 4 rows. The manifest of the FIRST call adds its four parts: instructions,
    // tools, options and the one ambient section = 4 rows. The SECOND call is made of the same
    // four, each already stored under the same reference, so it adds nothing. Eight in all;
    // twelve would mean the second call wrote its parts again.
    assertThat(rows(agent)).isEqualTo(8);
  }

  @Test
  void a_changed_ambient_section_stores_one_new_payload() {
    AtomicInteger asked = new AtomicInteger();
    AmbientSource ticking =
        AmbientSource.of(
            source ->
                source
                    .kind("clock")
                    .offering(
                        _ ->
                            Optional.of(Ambient.text("clock", "tick " + asked.incrementAndGet()))));

    AgentId agent = oneTurnTwoCalls(ticking);

    // The same eight as when nothing changes, and one more: the second call's ambient section
    // says "tick 2" where the first said "tick 1", so it is a new payload and its other three
    // parts are not.
    assertThat(asked.get()).isEqualTo(2);
    assertThat(rows(agent)).isEqualTo(9);
  }

  @Test
  void only_the_ambient_reference_differs_between_two_manifests_when_the_ambient_changed() {
    AgentId agent = AgentId.random();
    AtomicInteger asked = new AtomicInteger();
    IntFunction<InferenceContext> context =
        n ->
            new InferenceContext(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new Turn(
                    new TurnId(1),
                    new Input(new Seq(1), List.of(new Block.Text("q"))),
                    List.of(),
                    null),
                List.of(Ambient.text("clock", "tick " + n)));
    DefaultInferenceService service =
        new DefaultInferenceService(
            invocation -> context.apply(asked.incrementAndGet()),
            (request, narrator) ->
                new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported()),
            new SystemPrompt("You are terse."),
            List.of(
                new ToolOffer(
                    new ToolName("lookup"),
                    "looks a thing up",
                    new JsonSchema("{\"type\":\"object\"}"))),
            Narrator.silent(),
            Optional.empty(),
            engine.payloads());
    InferenceInvocation invocation =
        new InferenceInvocation(TYPE, agent, InferenceOptions.of("a-model"));

    InferenceRequestManifest first = service.infer(invocation).manifest();
    long afterFirst = rows(agent);
    InferenceRequestManifest second = service.infer(invocation).manifest();

    assertThat(rows(agent)).isEqualTo(afterFirst + 1);
    assertThat(second.ambient()).isNotEqualTo(first.ambient());
    assertThat(second).usingRecursiveComparison().ignoringFields("ambient").isEqualTo(first);
  }

  @Test
  void the_same_context_inferred_twice_stores_nothing_new_and_names_the_same_things() {
    AgentId agent = AgentId.random();
    InferenceContext context =
        new InferenceContext(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Turn(
                new TurnId(1),
                new Input(new Seq(1), List.of(new Block.Text("q"))),
                List.of(),
                null),
            List.of(Ambient.text("clock", "noon")));
    DefaultInferenceService service =
        new DefaultInferenceService(
            invocation -> context,
            (request, narrator) ->
                new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported()),
            new SystemPrompt("You are terse."),
            List.of(
                new ToolOffer(
                    new ToolName("lookup"),
                    "looks a thing up",
                    new JsonSchema("{\"type\":\"object\"}"))),
            Narrator.silent(),
            Optional.empty(),
            engine.payloads());
    InferenceInvocation invocation =
        new InferenceInvocation(TYPE, agent, InferenceOptions.of("a-model"));

    InferenceRequestManifest first = service.infer(invocation).manifest();
    long afterFirst = rows(agent);
    InferenceRequestManifest second = service.infer(invocation).manifest();

    assertThat(afterFirst).isPositive();
    assertThat(rows(agent)).isEqualTo(afterFirst);
    assertThat(second).isEqualTo(first);
  }
}
