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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * The whole loop, closed: an input goes in one door, and an answer the model gave comes back
 * through the other and lands in the story.
 *
 * <p>Nothing here drives a dispatcher by hand. The point is that the harness polls on its own -- so
 * this is the one test that lets the schedule run, at an interval short enough to wait on.
 *
 * <p>The model is a stub written by hand rather than a mocking library: what is needed is a model
 * that answers and records what it was asked, and that is nine lines.
 */
class HarnessLoopTest {

  private static final AgentType CHAT = new AgentType("chat");

  private final RecordingModel model = new RecordingModel();
  private EngineFixture engine;
  private QueuedHarness<String> harness;

  /**
   * The model belongs to the engine and the harness to the agent type, which is the whole of what
   * an application configures. Built here rather than injected, so this test carries no application
   * of its own.
   */
  @BeforeEach
  void startEngine() {
    engine = new EngineFixture(model);
    harness =
        engine
            .harnesses()
            .create(
                CHAT,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  private AgentEvent.TurnStarted observed(AgentId agent, long seq, String text) {
    return engine.turnStarted(agent, seq, text);
  }

  /** The whole record, flattened -- what was stored, not what would be sent. */
  private List<AgentEvent> story(AgentType agentType, AgentId agentId) {
    return engine.story(agentType, agentId);
  }

  @Test
  void anInputBecomesAModelCallAndTheAnswerLandsInTheStory() {
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.tell(agentId, "what is nessy?");

    // The input is recorded and the model call owed in the same transaction as the
    // state, so the story shows the question before anything has been asked.
    assertThat(story(CHAT, agentId)).containsExactly(observed(agentId, 1, "what is nessy?"));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(story(CHAT, agentId))
                    .containsExactly(
                        observed(agentId, 1, "what is nessy?"),
                        engine.answered(agentId, 2, 1, "a lake monster")));

    assertThat(model.asked())
        .as("the call is built from the story: one turn, still open, carrying the question")
        .singleElement()
        .satisfies(
            turns ->
                assertThat(turns)
                    .singleElement()
                    .satisfies(
                        turn -> {
                          assertThat(turn.id()).isEqualTo(new TurnId(1));
                          assertThat(turn.complete())
                              .as("the turn being asked about has no result yet")
                              .isFalse();
                          assertThat(turn.input().blocks())
                              .containsExactly(new Block.Text("what is nessy?"));
                        }));
  }

  /**
   * A second input arriving while the first turn is still open waits in the backlog, and opens its
   * own turn as that one closes -- so the story ends with both questions answered, in the order
   * they were asked.
   */
  @Test
  void twoInputsAreAnsweredInOrder() {
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.tell(agentId, "first");
    harness.tell(agentId, "second");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(story(CHAT, agentId))
                    .containsExactly(
                        observed(agentId, 1, "first"),
                        engine.answered(agentId, 2, 1, "a lake monster"),
                        observed(agentId, 3, "second"),
                        engine.answered(agentId, 4, 3, "a lake monster")));
  }

  /** A model that always answers the same thing, and remembers what it was asked. */
  static class RecordingModel implements InferenceProvider {

    private final List<List<Turn>> asked = new CopyOnWriteArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      asked.add(List.copyOf(request.context().turns()));
      return new InferenceResult.Answer(List.of(new Block.Text("a lake monster")));
    }

    List<List<Turn>> asked() {
      return asked;
    }
  }
}
