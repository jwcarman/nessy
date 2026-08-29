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
package org.jwcarman.nessy.examples.watchman;

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
import org.jwcarman.nessy.api.agent.Coalescer;
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Backlogs;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.SubstrateBacklogs;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The crash window {@code startTurnIfWork} used to leave open: {@code remember(userMessage)} then
 * {@code backlogs().taken()} then {@code persist(next)}. A crash between {@code taken()} and {@code
 * persist} strands the observation — gone from the backlog, present in the transcript, but the
 * persisted state still shows the pre-turn phase, so nothing ever re-asks the model. {@link
 * AgentState#takenEntryId} plus {@link AgentActor#resume} close it: the record of what was taken
 * lands durably BEFORE the backlog entry is removed, and recovery finishes an interrupted removal.
 *
 * <p>Reproducing the actual race is not practical (the crash window is a few nanoseconds inside one
 * {@code thenRun}), so this test manufactures the torn state directly — exactly what a crash in
 * that window would have left on disk — by writing straight to the durable-state store the actor
 * itself reads from, bypassing the actor entirely. That is the state {@code resume} must repair.
 */
@DisplayName("Recovering from a crash between taking a backlog entry and persisting the turn")
class BacklogRecoveryTest {

  private WatchmanActorSystem actors;

  @AfterEach
  void stop() {
    if (actors != null) {
      actors.stop();
    }
  }

  @Test
  void a_stranded_backlog_entry_is_removed_and_the_turn_proceeds_on_recovery() throws Exception {
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Memories memories = new Memories(substrate, 8000);
    Backlogs<String> backlogs = new SubstrateBacklogs<>(substrate, Coalescer.none(), String.class);
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedWatchmanModel(Duration.ofMillis(20)),
            new FakeRunner(),
            substrate,
            Coalescer.none(),
            8000,
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10));
    actors.start();
    String agent = "recovery-" + UUID.randomUUID();

    // What startTurnIfWork's remember() step would have done, and what its persist() step named,
    // for an observation still sitting in the backlog -- the exact shape of the crash window.
    backlogs.ingest(agent, "stranded", Instant.now());
    Backlogs.Taken<String> taken = backlogs.next(agent).orElseThrow();
    memories.forAgent(agent).remember(AgentActor.userMessage(WatchmanObservations.RENDERER, taken));
    AgentState torn =
        AgentState.idle()
            .startingTurn("turn-stuck")
            .withPhase(new Phase.CallingModel())
            .taking(taken.entryId());

    // Write the torn state straight into the store the actor reads on recovery -- no actor
    // involved, so this is genuinely "what a crash left on disk", not a message the actor handled.
    PersistenceTestKitDurableStateStore<Object> store =
        DurableStateStoreRegistry.get(actors.raw())
            .getDurableStateStoreFor(
                PersistenceTestKitDurableStateStore.class, "pekko.persistence.testkit.state");
    store
        .upsertObject("Watchman|" + agent, 1, torn, "")
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

    // Bring the actor into memory: this is where recovery -- and AgentActor#resume -- runs.
    actors.tell(agent, new AgentActor.Wake(Map.of()));

    // The stranded entry is removed, not left dangling forever.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(backlogs.next(agent)).isEmpty());

    // And the turn actually moves -- resume's CallingModel arm re-asks the model rather than
    // leaving the agent parked forever on a phase nothing will ever revisit.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              AgentState state =
                  actors.inspect(agent).toCompletableFuture().get(10, TimeUnit.SECONDS);
              assertThat(state.phase()).isNotInstanceOf(Phase.CallingModel.class);
            });
  }
}
