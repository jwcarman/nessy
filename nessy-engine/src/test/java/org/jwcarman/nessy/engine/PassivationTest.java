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

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;

/**
 * When an agent asks to be unloaded, and when it must not.
 *
 * <p>The rule the phase actors' deadlines depend on. A turn actor is a child, so passivating
 * mid-turn kills it — and with it the timers holding an approval's term and a deferral's expiry.
 * Nothing else would fire them, so a parked call would quietly outlive its deadline. Staying
 * resident while a turn is in flight is what makes those timers sufficient and a sweeper
 * unnecessary.
 */
@DisplayName("An agent deciding whether to stay in memory")
class PassivationTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");

  private static ActorTestKit testKit;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  private static ActorRef<NessyMessage> agentWith(
      Turns turns, AgentId agentId, ActorRef<ClusterSharding.ShardCommand> shard) {
    AgentActor.Dependencies<HouseEvent> deps =
        new AgentActor.Dependencies<>(
            WATCHMAN,
            HouseEvents.CODEC,
            HouseEvents.KEEP_ALL,
            HouseEvents.RENDERER,
            turns,
            Clock.systemUTC());
    return testKit.spawn(AgentActor.create(deps, agentId, shard));
  }

  private static void observe(ActorRef<NessyMessage> agent, HouseEvent event) {
    agent.tell(new NessyMessage.Observe(HouseEvents.CODEC.encode(event), Map.of()));
  }

  @Test
  @DisplayName("it stays resident while a turn is in flight")
  void an_agent_working_a_turn_does_not_ask_to_be_unloaded() {
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    // A turn that starts and never finishes: the agent is busy for good.
    ActorRef<NessyMessage> agent =
        agentWith((id, turnId, input, a) -> Behaviors.empty(), AgentId.of("busy-1"), shard.ref());

    observe(agent, new HouseEvent("kitchen", "door opened"));

    shard.expectNoMessage(Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("it asks to be unloaded once there is nothing left to do")
  void an_idle_agent_asks_the_shard_to_passivate_it() {
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    ActorRef<NessyMessage> agent =
        agentWith(
            (id, turnId, input, a) -> {
              a.tell(new NessyMessage.TurnFinished(turnId, Map.of()));
              return Behaviors.empty();
            },
            AgentId.of("idle-1"),
            shard.ref());

    observe(agent, new HouseEvent("hall", "motion"));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () ->
                assertThat(shard.receiveMessage(Duration.ofSeconds(1)))
                    .isInstanceOf(ClusterSharding.Passivate.class));
  }

  @Test
  void a_backlog_keeps_it_awake() {
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    ActorRef<NessyMessage> agent =
        agentWith((id, turnId, input, a) -> Behaviors.empty(), AgentId.of("busy-2"), shard.ref());

    observe(agent, new HouseEvent("kitchen", "one"));
    observe(agent, new HouseEvent("kitchen", "two"));

    shard.expectNoMessage(Duration.ofSeconds(2));
  }
}
