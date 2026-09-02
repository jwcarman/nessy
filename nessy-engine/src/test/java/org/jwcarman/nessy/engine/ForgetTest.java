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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
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
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * An agent being forgotten, against a real database.
 *
 * <p>The logic tests say what an agent DECIDES; this says what is actually gone afterwards. The
 * rows are the point — an agent instance that cannot end is a permanent transcript, and this is the
 * test that would fail if forgetting quietly deleted nothing.
 */
@DisplayName("Forgetting an agent")
class ForgetTest {

  private static ActorTestKit testKit;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  private static AgentId shardedAgent(
      String name, Engines.Parts parts, TestProbe<ClusterSharding.ShardCommand> shard) {
    AgentType type = AgentType.of(name);
    EntityTypeKey<NessyMessage> key = EntityTypeKey.create(NessyMessage.class, type.name());
    ClusterSharding.get(testKit.system())
        .init(
            Entity.of(
                    key,
                    context ->
                        AgentActor.create(
                            new AgentActor.Dependencies(type, parts.instructions(), Traces.noop()),
                            AgentId.of(context.getEntityId()),
                            shard.ref()))
                .withStopMessage(new NessyMessage.Stop(Map.of())));
    return AgentId.of(name + "-1");
  }

  /** Straight at the table: the point of this test is which rows survive. */
  private static int backlogRows(Engines.Parts parts, AgentId agentId) {
    Integer rows =
        org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
            .sql("SELECT count(*) FROM nessy_backlog WHERE agent_id = ?")
            .param(agentId.value())
            .query(Integer.class)
            .single();
    return rows == null ? 0 : rows;
  }

  /** Whether the pill itself — not merely its symptoms — is still sitting in the table. */
  private static int poisonRows(Engines.Parts parts, AgentId agentId) {
    Integer rows =
        org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
            .sql("SELECT count(*) FROM nessy_poison WHERE agent_id = ?")
            .param(agentId.value())
            .query(Integer.class)
            .single();
    return rows == null ? 0 : rows;
  }

  private static void tell(String typeName, AgentId agentId, NessyMessage message) {
    ClusterSharding.get(testKit.system())
        .entityRefFor(EntityTypeKey.create(NessyMessage.class, typeName), agentId.value())
        .tell(message);
  }

  @Test
  @DisplayName("an idle agent's memory, backlog and claims are gone afterwards")
  void forgetting_an_idle_agent_leaves_nothing() {
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    Engines.Parts parts =
        Engines.of(
            testKit.system(),
            AgentType.of("ephemeral"),
            Engines.saying(
                java.util.List.of(
                    new org.jwcarman.nessy.api.model.ModelResult.Answered(
                        new org.jwcarman.nessy.api.message.AnswerMessage(
                            java.util.List.of(new org.jwcarman.nessy.api.block.TextBlock("done"))),
                        org.jwcarman.nessy.api.model.StopReason.END_TURN,
                        org.jwcarman.nessy.api.model.Usage.unreported()))));
    AgentId agentId = shardedAgent("ephemeral", parts, shard);

    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
    tell("ephemeral", agentId, new NessyMessage.BacklogUpdated(Map.of()));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(parts.remembered().of(agentId)).isNotEmpty());

    parts.backlog().poison(agentId);
    tell("ephemeral", agentId, new NessyMessage.BacklogUpdated(Map.of()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(parts.remembered().of(agentId)).as("its memory").isEmpty();
              assertThat(backlogRows(parts, agentId)).as("its backlog rows").isZero();
            });
  }

  @Test
  @DisplayName("a busy agent finishes its turn first, and is gone after")
  void forgetting_a_busy_agent_does_not_strand_the_turn() {
    // The failure this design exists to avoid: deleting under a running turn leaves the model's
    // answer arriving at a dead incarnation with nobody left to finish anything.
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    Engines.Parts parts =
        Engines.of(
            testKit.system(),
            AgentType.of("mid-turn"),
            Engines.saying(
                java.util.List.of(
                    new org.jwcarman.nessy.api.model.ModelResult.Answered(
                        new org.jwcarman.nessy.api.message.AnswerMessage(
                            java.util.List.of(new org.jwcarman.nessy.api.block.TextBlock("done"))),
                        org.jwcarman.nessy.api.model.StopReason.END_TURN,
                        org.jwcarman.nessy.api.model.Usage.unreported()))));
    AgentId agentId = shardedAgent("mid-turn", parts, shard);

    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
    tell("mid-turn", agentId, new NessyMessage.BacklogUpdated(Map.of()));
    parts.backlog().poison(agentId);
    tell("mid-turn", agentId, new NessyMessage.BacklogUpdated(Map.of()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(parts.remembered().of(agentId)).isEmpty();
              assertThat(backlogRows(parts, agentId)).isZero();
            });
  }

  @Test
  @DisplayName("a forgotten id is usable again — the pill does not outlive the agent it named")
  void a_forgotten_agent_id_can_be_used_again() {
    // The design's own booby trap: swallow() is the LAST step of forgetting, deliberately, so a
    // crash before it leaves the pill behind. Leaving it forever has no visible cause of its own —
    // the next incarnation of a reusable id would simply never work, poisoned by a forget nobody
    // watching it would ever connect to the symptom.
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    Engines.Parts parts =
        Engines.of(
            testKit.system(),
            AgentType.of("reusable"),
            Engines.saying(
                java.util.List.of(
                    new org.jwcarman.nessy.api.model.ModelResult.Answered(
                        new org.jwcarman.nessy.api.message.AnswerMessage(
                            java.util.List.of(new org.jwcarman.nessy.api.block.TextBlock("done"))),
                        org.jwcarman.nessy.api.model.StopReason.END_TURN,
                        org.jwcarman.nessy.api.model.Usage.unreported()))));
    AgentId agentId = shardedAgent("reusable", parts, shard);

    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
    tell("reusable", agentId, new NessyMessage.BacklogUpdated(Map.of()));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(parts.remembered().of(agentId)).isNotEmpty());

    parts.backlog().poison(agentId);
    tell("reusable", agentId, new NessyMessage.BacklogUpdated(Map.of()));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(parts.remembered().of(agentId)).isEmpty();
              // The pill is swallowed LAST, deliberately — offering new work before it is gone
              // would have this second offer deleted by the very forget that is still finishing,
              // since `deleteAgent` takes every row for this id and a still-present pill means
              // the next take reads Poisoned rather than the row just offered.
              assertThat(poisonRows(parts, agentId)).as("the pill itself").isZero();
            });

    // A new incarnation of the SAME id, given SAME work. If the pill survived forgetting, the next
    // take would find it poisoned again and this agent would never run — silently, with nothing in
    // this test's view pointing at why.
    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened again"));
    tell("reusable", agentId, new NessyMessage.BacklogUpdated(Map.of()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(parts.remembered().of(agentId))
                    .as("the reused id ran a turn instead of being poisoned by the old pill")
                    .isNotEmpty());
  }

  @Test
  @DisplayName("forgetting an agent that never existed is silent")
  void forgetting_nothing_is_not_an_error() {
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    Engines.Parts parts =
        Engines.of(
            testKit.system(),
            AgentType.of("stranger"),
            Engines.saying(
                java.util.List.of(
                    new org.jwcarman.nessy.api.model.ModelResult.Answered(
                        new org.jwcarman.nessy.api.message.AnswerMessage(
                            java.util.List.of(new org.jwcarman.nessy.api.block.TextBlock("done"))),
                        org.jwcarman.nessy.api.model.StopReason.END_TURN,
                        org.jwcarman.nessy.api.model.Usage.unreported()))));
    AgentId agentId = shardedAgent("stranger", parts, shard);

    parts.backlog().poison(agentId);
    tell("stranger", agentId, new NessyMessage.BacklogUpdated(Map.of()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(parts.remembered().of(agentId)).isEmpty());
  }
}
