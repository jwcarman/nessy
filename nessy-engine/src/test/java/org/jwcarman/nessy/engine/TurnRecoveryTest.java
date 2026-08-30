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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;

/**
 * What happens when a turn dies without finishing.
 *
 * <p>A claimed turn is a fact in the agent's own state, and a turn actor is not — so a crash leaves
 * the claim standing with nothing running it. An agent that trusted the claim alone would be
 * stranded for good: it would never start a turn, believing one is running, and never finish one,
 * because nothing is.
 *
 * <p>Simulated by a turn that stops the moment it starts, which is exactly what the agent sees
 * after a crash: a claim, and no child.
 */
@DisplayName("A turn that died without finishing")
class TurnRecoveryTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;
  private static final AtomicInteger turnsStarted = new AtomicInteger();

  @BeforeAll
  static void startCluster() {
    testKit = ClusterOfOne.start();
    StateTypes.of(testKit.system()).register(WATCHMAN, HouseEvent.class);

    Turns diesImmediately =
        (agentId, turnId, input, agent) -> {
          turnsStarted.incrementAndGet();
          return Behaviors.stopped();
        };

    AgentActor.Dependencies<HouseEvent> deps =
        new AgentActor.Dependencies<>(
            WATCHMAN,
            HouseEvents.CODEC,
            HouseEvents.KEEP_ALL,
            HouseEvents.RENDERER,
            diesImmediately,
            Clock.systemUTC());

    ClusterSharding.get(testKit.system())
        .init(
            Entity.of(
                    KEY,
                    context ->
                        AgentActor.create(
                            deps, AgentId.of(context.getEntityId()), context.getShard()))
                .withStopMessage(new NessyMessage.Stop(Map.of())));
  }

  @AfterAll
  static void stopCluster() {
    testKit.shutdownTestKit();
  }

  private static void tell(String agentId, NessyMessage message) {
    ClusterSharding.get(testKit.system()).entityRefFor(KEY, agentId).tell(message);
  }

  private static AgentState<?> inspect(String agentId) {
    TestProbe<AgentState<?>> probe = testKit.createTestProbe();
    tell(agentId, new NessyMessage.Inspect(probe.ref(), Map.of()));
    return probe.receiveMessage();
  }

  @Test
  @DisplayName("a claimed turn with nothing running it is started again on the next wake")
  void a_stranded_turn_is_picked_back_up() {
    tell(
        "house-12",
        new NessyMessage.Observe(
            HouseEvents.CODEC.encode(new HouseEvent("kitchen", "door opened")), Map.of()));

    await().atMost(10, SECONDS).untilAsserted(() -> assertThat(turnsStarted).hasValue(1));
    await()
        .atMost(10, SECONDS)
        .untilAsserted(() -> assertThat(inspect("house-12").busy()).isTrue());

    tell("house-12", new NessyMessage.Wake(Map.of()));

    await().atMost(10, SECONDS).untilAsserted(() -> assertThat(turnsStarted).hasValue(2));
  }

  @Test
  void the_observation_it_was_working_on_is_still_there_to_work_on() {
    tell(
        "house-13",
        new NessyMessage.Observe(
            HouseEvents.CODEC.encode(new HouseEvent("hall", "motion")), Map.of()));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              AgentState<?> state = inspect("house-13");
              assertThat(state.busy()).isTrue();
              assertThat(state.inFlight()).isNotNull();
              assertThat(state.inFlight().observation())
                  .isEqualTo(new HouseEvent("hall", "motion"));
            });
  }
}
