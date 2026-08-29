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
package org.jwcarman.nessy.examples.watchman.pekko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.typesafe.config.ConfigFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pekko.persistence.state.DurableStateStoreRegistry;
import org.apache.pekko.persistence.testkit.state.javadsl.PersistenceTestKitDurableStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Backlogs;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Claims;
import org.jwcarman.nessy.engine.Coalescer;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.SubstrateBacklogs;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The sibling crash window to {@link BacklogRecoveryTest}: {@link AgentActor#onObserve} commits to
 * the backlog with {@code Backlogs#ingest} and only THEN returns the persisted {@code Effect} Pekko
 * applies. A crash inside that DB-round-trip-wide window -- after the backlog write, before the
 * persist -- leaves the observation durably queued while the persisted state still shows {@code
 * Idle}. Nothing used to notice: {@code Wake} was a no-op, and {@link
 * StartupSweep#unfinishedAgents} only wakes agents whose phase is NOT idle, so the observation
 * would sit forever.
 *
 * <p>As with its sibling, reproducing the actual race is not practical, so this test manufactures
 * the torn state directly: an already-persisted {@code Idle} document, with a backlog entry that
 * was never taken, written straight into the durable-state store the actor reads from on recovery.
 */
@DisplayName("Recovering from a crash between an observation reaching the backlog and Idle leaving")
class IdleBacklogRecoveryTest {

  private WatchmanActorSystem actors;

  @AfterEach
  void stop() {
    if (actors != null) {
      actors.stop();
    }
  }

  @Test
  void an_idle_agent_with_a_stranded_backlog_entry_starts_a_turn_on_recovery() throws Exception {
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Memories memories = new Memories(substrate, 8000);
    Backlogs<String> backlogs = new SubstrateBacklogs<>(substrate, Coalescer.none(), String.class);
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedWatchmanModel(Duration.ofMillis(20)),
            new FakeRunner(),
            memories,
            backlogs,
            WatchmanObservations.RENDERER,
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            new Claims(substrate));
    actors.start();
    String agent = "idle-recovery-" + UUID.randomUUID();

    // What onObserve's ingest() step would have done, with nothing yet persisted past it -- the
    // exact shape of the crash window: the observation is durably queued, but the state document
    // (already on disk from some earlier round) still shows Idle.
    backlogs.ingest(agent, "stranded while idle", Instant.now());

    PersistenceTestKitDurableStateStore<Object> store =
        DurableStateStoreRegistry.get(actors.raw())
            .getDurableStateStoreFor(
                PersistenceTestKitDurableStateStore.class, "pekko.persistence.testkit.state");
    store
        .upsertObject("Watchman|" + agent, 1, AgentState.idle(), "")
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

    // Bring the actor into memory: this is where recovery -- and AgentActor#resume -- runs.
    actors.tell(agent, new AgentActor.Wake(Map.of()));

    // The stranded entry is drained, not left dangling forever.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(backlogs.next(agent)).isEmpty());

    // And a turn actually started -- resume's Idle arm does not leave the agent parked on Idle
    // forever while an observation nobody will ever revisit sits in its backlog.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              AgentState state =
                  actors.inspect(agent).toCompletableFuture().get(10, TimeUnit.SECONDS);
              assertThat(state.phase()).isNotInstanceOf(Phase.Idle.class);
            });
  }
}
