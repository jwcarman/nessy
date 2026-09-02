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
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;

/**
 * A safety classifier declining, rather than answering.
 *
 * <p>A refusal arrives as a normal HTTP 200 — the provider's own guidance is to check WHY the turn
 * stopped before reading anything else out of it. Nothing before this test exercised the path a
 * real refusal actually takes: {@code Instructions.answerOf} turning a {@link ModelResult.Refused}
 * into {@code NessyMessage.ModelRefused}, and the turn closing as {@link TurnResult.Refused} rather
 * than hanging or being mistaken for a normal answer.
 */
@DisplayName("A model that refuses instead of answering")
class ModelRefusalTest {

  private static final AgentType WATCHMAN = AgentType.of("refuser");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;
  private static Engines.Parts parts;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
    parts =
        Engines.of(
            testKit.system(),
            WATCHMAN,
            Engines.saying(
                List.of(
                    new ModelResult.Refused(
                        "harassment", "will not help with that", Usage.unreported()))));
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
  @DisplayName("the turn closes as refused, and nothing is remembered as though it answered")
  void a_refusal_ends_the_turn_without_a_recorded_answer() {
    AgentId agentId = AgentId.of("house-refused");
    parts.backlog().offer(agentId, new HouseEvent("porch", "someone at the door"));
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId.value())
        .tell(new NessyMessage.BacklogUpdated(Map.of()));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              List<AgentEvent.TurnEnded> ended =
                  parts.narrated().of(agentId).stream()
                      .filter(AgentEvent.TurnEnded.class::isInstance)
                      .map(AgentEvent.TurnEnded.class::cast)
                      .toList();
              assertThat(ended).hasSize(1);
              assertThat(ended.getFirst().outcome()).isInstanceOf(TurnResult.Refused.class);
              TurnResult.Refused refused = (TurnResult.Refused) ended.getFirst().outcome();
              assertThat(refused.category()).isEqualTo("harassment");
              assertThat(refused.explanation()).isEqualTo("will not help with that");
            });

    // The observation itself is remembered at the start of every turn, refused or not — that is
    // unrelated to this test. What must NOT appear is anything claiming the model answered.
    List<org.jwcarman.nessy.api.message.HistoryMessage> remembered = parts.remembered().of(agentId);
    assertThat(remembered).as("the observation that started the turn").isNotEmpty();
    assertThat(remembered)
        .as("a refusal is not an answer, and nothing here claimed it was one")
        .noneMatch(org.jwcarman.nessy.api.message.AnswerMessage.class::isInstance);
  }
}
