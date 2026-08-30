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
import java.util.List;
import java.util.Map;
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
 * An agent as a real sharded entity, with a turn that never finishes.
 *
 * <p>Stalling the turn on purpose: it is the only way to SEE the backlog, because a working turn
 * drains it. What is under test is the rule that makes the backlog meaningful — one turn at a time,
 * and the observation being worked on kept out of the coalescer's reach.
 */
@DisplayName("An agent receiving observations")
class AgentActorTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;

  @BeforeAll
  static void startCluster() {
    testKit = ClusterOfOne.start();
    StateTypes.of(testKit.system()).register(WATCHMAN, HouseEvent.class);

    // A turn that starts and then does nothing, ever. It never reports back, so the agent stays
    // busy and whatever else arrives has to queue.
    Turns stalled = (agentId, turnId, input, agent) -> Behaviors.empty();

    AgentActor.Dependencies<HouseEvent> deps =
        new AgentActor.Dependencies<>(
            WATCHMAN,
            HouseEvents.CODEC,
            HouseEvents.KEEP_ALL,
            HouseEvents.RENDERER,
            stalled,
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

  private static void observe(String agentId, HouseEvent event) {
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId)
        .tell(new NessyMessage.Observe(HouseEvents.CODEC.encode(event), Map.of()));
  }

  private static AgentState<?> inspect(String agentId) {
    TestProbe<AgentState<?>> probe = testKit.createTestProbe();
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId)
        .tell(new NessyMessage.Inspect(probe.ref(), Map.of()));
    return probe.receiveMessage();
  }

  /** Extracted separately: a capture of {@code AgentState<?>} defeats AssertJ's inference. */
  private static List<Object> waiting(AgentState<?> state) {
    return state.backlog().stream().map(item -> (Object) item.observation()).toList();
  }

  @Test
  void a_fresh_agent_is_idle_with_nothing_waiting() {
    AgentState<?> state = inspect("house-empty");

    assertThat(state.busy()).isFalse();
    assertThat(state.hasWork()).isFalse();
    assertThat(state.agentType()).isEqualTo(WATCHMAN);
  }

  @Test
  @DisplayName("the first observation is taken; the rest wait, as themselves not as maps")
  void one_turn_at_a_time_with_the_rest_queued() {
    observe("house-12", new HouseEvent("kitchen", "one"));
    observe("house-12", new HouseEvent("hall", "two"));
    observe("house-12", new HouseEvent("porch", "three"));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              AgentState<?> state = inspect("house-12");
              assertThat(state.busy()).isTrue();
              assertThat(state.inFlight().observation())
                  .isEqualTo(new HouseEvent("kitchen", "one"));
              assertThat(waiting(state))
                  .containsExactly(new HouseEvent("hall", "two"), new HouseEvent("porch", "three"));
            });
  }

  @Test
  void two_agent_ids_do_not_share_a_backlog() {
    observe("house-a", new HouseEvent("kitchen", "mine"));

    await().atMost(10, SECONDS).untilAsserted(() -> assertThat(inspect("house-a").busy()).isTrue());
    AgentState<?> other = inspect("house-b");
    assertThat(other.busy()).isFalse();
    assertThat(other.backlog()).isEmpty();
  }
}
