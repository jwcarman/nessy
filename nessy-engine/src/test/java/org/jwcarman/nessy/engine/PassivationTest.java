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

import java.time.Duration;
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
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;

/**
 * When an agent asks to be unloaded, and when it must not.
 *
 * <p>The rule is narrower than it was, and deliberately so. It used to be load-bearing: a turn was
 * a CHILD, so passivating mid-turn killed it along with the timers holding an approval's term and a
 * deferral's expiry. Staying resident was what made those timers sufficient.
 *
 * <p>None of that is true now — deadlines are rows and answers arrive at a logical address — so
 * this is ordinary economy rather than a correctness property. It is still worth asserting: an
 * agent that unloaded mid-turn would be doing needless work to wake back up, and one that never
 * unloaded would never free anything.
 */
@DisplayName("An agent deciding whether to stay in memory")
class PassivationTest {

  private static ActorTestKit testKit;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  /**
   * An agent reachable at its entity address, with a TEST PROBE standing in for the shard.
   *
   * <p>Sharding-initialized rather than merely spawned, because the shell addresses everything it
   * does — including the answer to its own request for work — to the LOGICAL address. A bare
   * spawned actor is not at that address, so it would ask for work and never hear back.
   *
   * <p>Each test gets its own agent type, so each gets its own entity key and its own probe.
   */
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

  private static void wake(String typeName, AgentId agentId) {
    ClusterSharding.get(testKit.system())
        .entityRefFor(EntityTypeKey.create(NessyMessage.class, typeName), agentId.value())
        .tell(new NessyMessage.BacklogUpdated(Map.of()));
  }

  @Test
  @DisplayName("it stays resident while a turn is in flight")
  void an_agent_working_a_turn_does_not_ask_to_be_unloaded() {
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    Engines.Parts parts = Engines.of(testKit.system(), AgentType.of("busy"), Engines.stalled());
    AgentId agentId = shardedAgent("busy", parts, shard);

    parts.backlog().offer(agentId, new HouseEvent("kitchen", "door opened"));
    wake("busy", agentId);

    // It asks to be unloaded only once there is nothing to do, and there always is here.
    await()
        .atMost(10, SECONDS)
        .untilAsserted(() -> assertThat(parts.narrated().all()).isNotEmpty());
    shard.expectNoMessage(Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("it asks to be unloaded once there is nothing left to do")
  void an_idle_agent_asks_the_shard_to_passivate_it() {
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    Engines.Parts parts =
        Engines.of(
            testKit.system(),
            AgentType.of("idle"),
            Engines.saying(
                List.of(
                    new ModelResult.Answered(
                        new AnswerMessage(List.of(new TextBlock("all quiet"))),
                        StopReason.END_TURN,
                        Usage.unreported()))));
    AgentId agentId = shardedAgent("idle", parts, shard);

    parts.backlog().offer(agentId, new HouseEvent("hall", "motion"));
    wake("idle", agentId);

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () ->
                assertThat(shard.receiveMessage(Duration.ofSeconds(1)))
                    .isInstanceOf(ClusterSharding.Passivate.class));
  }

  @Test
  @DisplayName("an agent with nothing to do at all still asks, so a cold start frees itself")
  void an_agent_that_never_had_work_asks_too() {
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    Engines.Parts parts = Engines.of(testKit.system(), AgentType.of("never"), Engines.stalled());
    AgentId agentId = shardedAgent("never", parts, shard);

    wake("never", agentId);

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () ->
                assertThat(shard.receiveMessage(Duration.ofSeconds(1)))
                    .isInstanceOf(ClusterSharding.Passivate.class));
  }
}
