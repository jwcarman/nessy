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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * Draining a backlog into a turn: ONE queued observation per turn, never the whole backlog joined
 * into one message — see the controller ruling in the ingest-backlog-and-claims task brief.
 * Coalescing several observations into one is a {@link Coalescer} decision made at ingest, not
 * something a drain gets to do on its own.
 */
@DisplayName("Draining a backlog into a turn")
class DrainTest {

  private WatchmanActorSystem actors;

  @AfterEach
  void stop() {
    if (actors != null) {
      actors.stop();
    }
  }

  private AgentState state(String agent) {
    try {
      return actors.inspect(agent).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void awaitParked(String agent) {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              AgentState current = state(agent);
              assertThat(current.phase()).isInstanceOf(Phase.WorkingTools.class);
              assertThat(Calls.pending(current, "prune_images")).isPresent();
            });
  }

  private void denyPending(String agent) throws Exception {
    String callId = Calls.pending(state(agent), "prune_images").orElseThrow();
    actors
        .answerApproval(agent, callId, false, "james", "no")
        .toCompletableFuture()
        .get(15, TimeUnit.SECONDS);
  }

  @Test
  void each_queued_observation_becomes_its_own_turn_not_one_merged_message() throws Exception {
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
    String agent = "drain-" + UUID.randomUUID();

    actors.tell(agent, new AgentActor.Observe("first", Map.of()));
    awaitParked(agent);

    // Arrives mid-round -- queued, not merged into the first turn's user message.
    actors.tell(agent, new AgentActor.Observe("second", Map.of()));
    denyPending(agent);

    // The round that just finished immediately starts the next one for what was queued.
    awaitParked(agent);
    denyPending(agent);

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(state(agent).phase()).isInstanceOf(Phase.Idle.class));

    // Plain user turns only -- tool-results messages carry Role.USER too, and are not what this
    // test is about.
    List<Message> users =
        memories.everything(agent).messages().stream()
            .filter(m -> m.role() == Role.USER)
            .filter(m -> m.content().stream().noneMatch(ToolResultBlock.class::isInstance))
            .toList();

    // TWO user turns, not one joined message -- each carrying exactly the one observation that
    // was queued for it.
    assertThat(users).hasSize(2);
    assertThat(users.get(0).content()).hasSize(1);
    assertThat(users.get(1).content()).hasSize(1);
  }

  @Test
  void remembering_the_same_drained_observation_twice_does_not_duplicate_it() {
    Memories memories = new Memories(new InMemorySubstrate(Clock.systemUTC()), 8000);
    Memory memory = memories.forAgent("redrain");
    Backlogs.Taken<String> taken = new Backlogs.Taken<>("entry-a", "one");

    memory.remember(AgentActor.userMessage(WatchmanObservations.RENDERER, taken));
    memory.remember(AgentActor.userMessage(WatchmanObservations.RENDERER, taken));

    assertThat(memories.everything("redrain").messages()).hasSize(1);
  }
}
