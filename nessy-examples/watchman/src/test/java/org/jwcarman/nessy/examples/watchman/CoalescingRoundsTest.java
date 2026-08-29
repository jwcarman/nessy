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
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Backlogs;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Calls;
import org.jwcarman.nessy.engine.Coalescer;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.SubstrateBacklogs;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The soak bug, in the spirit of the six-round run that produced it: a six-round soak against a
 * coalescing observation vocabulary landed exactly ONE {@code user-message} in the transcript,
 * because {@link Coalescer#byKey} used the coalescing key itself as the backlog entry id — so every
 * round's observation, however many rounds ran, derived the SAME {@code Remembrance} key ({@code
 * "obs:k:rounds"}) and {@code Memory}'s idempotence-by-key silently swallowed every round after the
 * first.
 *
 * <p>This test drives the watchman through two full rounds with {@link
 * WatchmanObservations#COALESCER} — the real "keep only the latest tick" coalescer the soak used —
 * and asserts the transcript ends up with TWO distinct user turns, not one. It is the assertion
 * that would have caught the soak's silent data loss; {@link CoalescerTest} exercises the reducer
 * in isolation but never drives it through an actual {@code Remembrance} key collision.
 */
@DisplayName("Two rounds of a coalescing observation both reach the transcript")
class CoalescingRoundsTest {

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

  private void awaitIdle(String agent) {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(state(agent).phase()).isInstanceOf(Phase.Idle.class));
  }

  @Test
  void six_rounds_of_a_coalescing_tick_produce_six_user_messages_not_one() throws Exception {
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Memories memories = new Memories(substrate, 8000);
    Backlogs<String> backlogs =
        new SubstrateBacklogs<>(substrate, WatchmanObservations.COALESCER, String.class);
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedWatchmanModel(Duration.ofMillis(20)),
            new FakeRunner(),
            substrate,
            WatchmanObservations.COALESCER,
            8000,
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10));
    actors.start();
    String agent = "coalescing-rounds-" + UUID.randomUUID();

    // Every round's observation carries the SAME coalescing key ("rounds") — the exact shape a
    // cron tick has, and the shape that let one Remembrance key swallow every round after the
    // first. Each round is driven to completion (parked on approval, denied, back to idle) before
    // the next observation arrives, so every ingest is a fresh append rather than a supersede —
    // which is precisely the case the old id-from-key scheme collided on.
    for (int round = 1; round <= 6; round++) {
      actors.tell(
          agent, new AgentActor.Observe("It is 12:0" + round + ". Do your rounds.", Map.of()));
      awaitParked(agent);
      denyPending(agent);
      awaitIdle(agent);
    }

    // Plain user turns only -- tool-results messages carry Role.USER too, and are not what this
    // test is about.
    List<Message> users =
        memories.everything(agent).messages().stream()
            .filter(m -> m.role() == Role.USER)
            .filter(m -> m.content().stream().noneMatch(ToolResultBlock.class::isInstance))
            .toList();

    // SIX rounds, SIX user turns -- not the one the soak's key collision left behind.
    assertThat(users).hasSize(6);
  }
}
