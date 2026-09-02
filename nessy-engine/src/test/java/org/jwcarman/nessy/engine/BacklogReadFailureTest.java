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
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
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
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

/**
 * A backlog table that cannot be reached when {@code take} runs.
 *
 * <p>Nothing before this test drove {@code Instructions.takeWork}'s failure branch: an agent asking
 * for work reports a FAILED turn — narrated, not silent — when the store cannot answer, rather than
 * hanging forever with no explanation reaching anyone watching. The failure is real SQL against a
 * real, now-unreachable, database, not a stand-in that only claims to fail.
 *
 * <p>The agent is settled to {@code Idle} — proven by {@code Inspect}, not assumed — BEFORE the
 * database goes down. Activation issues its own {@code TakeWork} independent of the one a {@code
 * BacklogUpdated} sends, and cutting the database first would let both race the same failure and
 * narrate it twice, which is a fact about startup rather than about the thing this test means to
 * pin.
 */
@DisplayName("A backlog the store cannot read")
class BacklogReadFailureTest {

  private static final AgentType WATCHMAN = AgentType.of("unreachable");
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
                    new ModelResult.Answered(
                        new AnswerMessage(List.of(new TextBlock("unreachable"))),
                        StopReason.END_TURN,
                        Usage.unreported()))));
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

  private static AgentState inspect(AgentId agentId) {
    TestProbe<AgentState> probe = testKit.createTestProbe();
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId.value())
        .tell(new NessyMessage.Inspect(probe.ref(), Map.of()));
    return probe.receiveMessage();
  }

  @Test
  @DisplayName("asking for work reports a failed turn instead of going silent")
  void a_take_that_cannot_reach_the_database_ends_the_turn_as_failed() {
    AgentId agentId = AgentId.of("house-unreachable");

    // Settle the agent first, against the real (still up) database: activation's own take finds
    // an empty backlog and the agent goes idle, exactly once, before the database is touched.
    await().atMost(15, SECONDS).until(() -> !inspect(agentId).busy());

    // Down for good, deliberately: the point is a `take` that cannot complete, not one that is
    // merely slow.
    ((EmbeddedDatabase) parts.dataSource()).shutdown();

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
              assertThat(ended.getFirst().outcome()).isInstanceOf(TurnResult.Failed.class);
              TurnResult.Failed failed = (TurnResult.Failed) ended.getFirst().outcome();
              assertThat(failed.reason()).contains("the backlog could not be read");
            });
  }
}
