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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * What an agent says about itself while it works.
 *
 * <p>Narration is best-effort and never durable, so what is worth pinning is not that every event
 * arrives but that the ones a watcher cannot get anywhere else do -- and that a watcher can never
 * hurt the agent.
 */
class NarrationTest {

  private EngineFixture engine;

  /**
   * A narrator is a factory-level seam, not a per-harness one, so varying it means varying the
   * engine. Every test but one runs against the recording sink installed below.
   */
  private void narratedBy(NarrationListener narrator) {
    if (engine != null) {
      engine.close();
    }
    engine = new EngineFixture(callsThenAnswers(), narrator);
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  /**
   * Everything narrated during this class's run.
   *
   * <p>Static because there is deliberately no per-harness override: one sink is told about every
   * agent and routes on what it is told, which is exactly what this does. Cleared between tests.
   */
  private static final ConcurrentLinkedQueue<Narration> EVENTS = new ConcurrentLinkedQueue<>();

  /**
   * The engine's one narrator.
   *
   * <p>A narrator is a factory-level seam for the same reason the provider is: it is told which
   * agent each event belongs to, so one sink serves every agent type and routes on what it is
   * handed rather than being installed per harness.
   */
  private static final NarrationListener RECORDING = (_, _, event) -> EVENTS.add(event);

  @BeforeEach
  void forgetWhatWasSaid() {
    EVENTS.clear();
    narratedBy(RECORDING);
  }

  record Query(String q) {}

  private Tool<Query> lookup() {
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

  /** The state replay produces, named -- there is no state column to read. */
  private String agentStateOf(AgentType agentType, AgentId agentId) {
    return engine.stateOf(agentType, agentId).getClass().getSimpleName();
  }

  private static InferenceProvider callsThenAnswers() {
    return (request, _) ->
        request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
            ? new InferenceResult.Answer(List.of(new Block.Text("It is 1412 metres deep.")))
            : new InferenceResult.Actions(
                List.of(
                    new Block.Commentary("Let me look that up."),
                    new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));
  }

  private <T extends Narration> List<T> of(Class<T> kind) {
    return EVENTS.stream().filter(kind::isInstance).map(kind::cast).toList();
  }

  /**
   * A whole turn, told to a watcher. What matters is the order and that nothing is invented: every
   * fact here is announced after the fold that made it true.
   */
  @Test
  void aTurnWithAToolCallIsNarratedEndToEnd() {
    AgentType type = new AgentType("narrated");
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness(type).tell(agentId, "how deep is Loch Ness?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(type, agentId)).isEqualTo("Idle"));

    assertThat(EVENTS)
        .extracting(event -> event.getClass().getSimpleName())
        .containsSubsequence(
            "TurnStarted",
            "Thinking",
            "Commentary",
            "ActionsRequested",
            "ApprovalSought",
            "CallApproved",
            "CallFinished",
            "Thinking",
            "Answered");

    // Both say THAT it happened and neither repeats what was said: the words are the caller's
    // return value and the story's business, and narration that echoed them would be a third copy.
    assertThat(of(Narration.TurnStarted.class)).singleElement().isNotNull();
    assertThat(of(Narration.Answered.class)).singleElement().isNotNull();
  }

  /**
   * A finished call names only its id, so the request has to say which tool that id is -- or a
   * watcher with two calls in flight cannot tell which one finished.
   */
  @Test
  void aFinishedCallJoinsToItsToolByTheIdTheRequestAnnounced() {
    AgentType type = new AgentType("narrated-join");
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness(type).tell(agentId, "how deep is Loch Ness?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(type, agentId)).isEqualTo("Idle"));

    List<Narration.ActionsRequested.Call> asked =
        of(Narration.ActionsRequested.class).getFirst().calls();
    assertThat(asked).isNotEmpty();
    CallId finished = of(Narration.CallFinished.class).getFirst().callId();

    assertThat(asked)
        .filteredOn(call -> call.callId().equals(finished))
        .singleElement()
        .satisfies(call -> assertThat(call.toolName()).isEqualTo(new ToolName("lookup")));
  }

  /**
   * The sentence the model wrote while working, told apart from the answer by the block it arrived
   * as rather than by whether the message happened to carry calls.
   */
  @Test
  void commentaryIsNarratedSeparatelyFromTheAnswer() {
    AgentType type = new AgentType("narrated-commentary");
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness(type).tell(agentId, "how deep is Loch Ness?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(type, agentId)).isEqualTo("Idle"));

    assertThat(of(Narration.Commentary.class))
        .singleElement()
        .satisfies(said -> assertThat(said.text()).isEqualTo("Let me look that up."));
    // And the commentary is its own event rather than being folded into the answer, which is the
    // distinction this test exists for.
    assertThat(of(Narration.Answered.class)).singleElement().isNotNull();
  }

  /**
   * The arm that pays for the whole channel.
   *
   * <p>"Awaiting a human" is the state an operator most wants to see, and the fold deliberately
   * never learns it -- a tool that takes three days and one that takes 200ms are the same thing to
   * the state machine. So this is the only place it exists, and if it is not announced here it is
   * not anywhere.
   */
  @Test
  void aQuestionWaitingOnAPersonIsTheOneThingOnlyNarrationCanSay() {
    AgentType type = new AgentType("narrated-deferred");
    AgentId agentId = new AgentId(UUID.randomUUID());

    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            lookup(),
                            t ->
                                t.action(query -> "look up " + query.q())
                                    .approver(
                                        _ -> Awaited.deferred(),
                                        a -> a.timeout(Duration.ofMinutes(30))))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.tell(agentId, "how deep is Loch Ness?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(of(Narration.ApprovalDeferred.class)).isNotEmpty());

    assertThat(of(Narration.ApprovalDeferred.class))
        .singleElement()
        .satisfies(
            waiting -> {
              assertThat(waiting.callId()).isEqualTo(new CallId("call_1"));
              assertThat(waiting.action())
                  .as("what a person is being asked, not which call id is outstanding")
                  .isEqualTo("look up loch ness");
              assertThat(waiting.until()).isNotNull();
            });
    assertThat(agentStateOf(type, agentId))
        .as("and the state says only that a call is outstanding, as designed")
        .isEqualTo("AwaitingActions");
  }

  /**
   * A watcher must never be able to fail a turn.
   *
   * <p>Narration is best-effort by contract, and this is what that contract is worth: a sink that
   * throws on every event is logged and ignored, and the agent finishes exactly as it would have
   * with nobody listening.
   */
  @Test
  void aNarratorThatThrowsCannotBreakTheAgent() {
    narratedBy(
        (_, _, _) -> {
          throw new IllegalStateException("this sink is broken");
        });
    AgentType type = new AgentType("narrated-broken");
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness(type).tell(agentId, "how deep is Loch Ness?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(type, agentId)).isEqualTo("Idle");
              assertThat(
                      engine
                          .jdbc()
                          .sql("SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?")
                          .params(agentId.value())
                          .query(Integer.class)
                          .single())
                  .isZero();
            });
  }

  /** An ordinary harness -- the narration comes from the engine's narrator, not from here. */
  private QueuedHarness<String> harness(AgentType type) {
    return engine
        .harnesses()
        .create(
            type,
            String.class,
            config ->
                config
                    .systemPrompt("You are a test assistant.")
                    .tool(lookup(), t -> t.action(query -> "look up " + query.q()))
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }
}
