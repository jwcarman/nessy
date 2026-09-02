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
package org.jwcarman.nessy.engine;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * A provider that shows its reasoning as it streams.
 *
 * <p>{@code Instructions.narrateChunk} paints a {@link ModelEvent.ReasoningChunk} the same way it
 * paints prose — as it arrives, on the thread draining the stream. {@link Scripts}, used by every
 * other test here, only ever produces prose and tool calls, so nothing before this test built a
 * stream carrying reasoning. Reasoning is narration only: it is never assembled into the message
 * {@code Memory} is told about, which this test also pins by checking what got remembered.
 */
@DisplayName("A model that streams its reasoning before its answer")
class ReasoningNarrationTest {

  private static final AgentType WATCHMAN = AgentType.of("thinker");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;
  private static Engines.Parts parts;

  private static Model reasoningThenAnswering() {
    List<ModelEvent> events =
        List.of(
            new ModelEvent.ReasoningChunk("turning the porch light on seems safe"),
            new ModelEvent.TextChunk("done"),
            new ModelEvent.Stopped(StopReason.END_TURN, Usage.unreported()));
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("thinker");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        return new ModelStream() {
          @Override
          public Iterator<ModelEvent> iterator() {
            return events.iterator();
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
      }
    };
  }

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
    parts = Engines.of(testKit.system(), WATCHMAN, reasoningThenAnswering());
    ClusterSharding.get(testKit.system())
        .init(
            Entity.of(
                    KEY,
                    context ->
                        AgentActor.create(
                            new AgentActor.Dependencies(
                                WATCHMAN, parts.instructions(), Traces.noop()),
                            AgentId.of(context.getEntityId()),
                            context.getShard()))
                .withStopMessage(new NessyMessage.Stop(Map.of())));
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @Test
  @DisplayName("the reasoning is narrated, and never remembered as part of the answer")
  void reasoning_chunks_narrate_and_the_answer_alone_is_remembered() {
    AgentId agentId = AgentId.of("house-thinker");
    parts.backlog().offer(agentId, new HouseEvent("porch", "motion detected"));
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId.value())
        .tell(new NessyMessage.BacklogUpdated(Map.of()));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              List<AgentEvent.ReasoningDelta> deltas =
                  parts.narrated().of(agentId).stream()
                      .filter(AgentEvent.ReasoningDelta.class::isInstance)
                      .map(AgentEvent.ReasoningDelta.class::cast)
                      .toList();
              assertThat(deltas).hasSize(1);
              assertThat(deltas.getFirst().text())
                  .isEqualTo("turning the porch light on seems safe");
            });

    List<org.jwcarman.nessy.api.message.AnswerMessage> answers =
        parts.remembered().of(agentId).stream()
            .filter(org.jwcarman.nessy.api.message.AnswerMessage.class::isInstance)
            .map(org.jwcarman.nessy.api.message.AnswerMessage.class::cast)
            .toList();
    assertThat(answers).hasSize(1);
    assertThat(answers.getFirst().content())
        .as("only the assembled prose reaches memory, never the reasoning")
        .hasSize(1)
        .allSatisfy(
            block ->
                assertThat(block)
                    .isInstanceOf(org.jwcarman.nessy.api.block.TextBlock.class)
                    .extracting(b -> ((org.jwcarman.nessy.api.block.TextBlock) b).text())
                    .isEqualTo("done"));
  }
}
