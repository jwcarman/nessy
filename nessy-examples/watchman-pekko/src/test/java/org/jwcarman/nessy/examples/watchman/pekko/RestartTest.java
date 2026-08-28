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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THE PROOF THE SOAK IS ABOUT: a round parked on a human survives the box being restarted.
 *
 * <p>The sibling watchman got this from Continuum plus a stalled-turn sweep. Here it comes from a
 * persisted decision, an actor respawned by {@link StartupSweep}, and nothing else.
 */
@Tag("container")
@DisplayName("A parked round, across a restart")
class RestartTest {

  private static final Duration PATIENCE = Duration.ofSeconds(45);

  private AgentState stateOf(WatchmanActorSystem actors, String agent) {
    try {
      return actors.inspect(agent).toCompletableFuture().get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The pending prune call's id, which is unique per call and never hardcoded. */
  private static String prune(WatchmanActorSystem actors, String agent) {
    try {
      return Calls.pending(
              actors.inspect(agent).toCompletableFuture().get(20, TimeUnit.SECONDS), "prune_images")
          .orElseThrow();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void a_round_parked_on_an_approval_is_answered_after_a_restart_and_finishes() throws Exception {
    String agent = "restart-" + UUID.randomUUID();

    WatchmanActorSystem first =
        WatchmanPostgres.start(new ScriptedWatchmanModel(Duration.ofMillis(20)));
    try {
      first.tell(
          agent,
          new AgentActor.Observe("It is noon. Do your rounds.", "rounds", java.util.Map.of()));
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> {
                AgentState state = stateOf(first, agent);
                assertThat(state.phase()).isInstanceOf(Phase.WorkingTools.class);
                assertThat(Calls.pending(state, "prune_images")).isPresent();
              });
    } finally {
      first.stop(); // a real termination, awaited
    }

    // A genuinely new actor system, which has never heard of this round.
    WatchmanActorSystem second =
        WatchmanPostgres.start(new ScriptedWatchmanModel(Duration.ofMillis(20)));
    try {
      // The approvals page finds it by reading the agents' own persisted state -- no projection,
      // no second write, and no live actor required.
      var pending =
          new PendingApprovals(WatchmanPostgres.dataSource(), Clock.systemUTC()).pending();
      assertThat(pending).isNotEmpty();
      assertThat(pending)
          .anySatisfy(
              row -> {
                assertThat(row.agentId()).isEqualTo(agent);
                assertThat(row.tool()).isEqualTo("prune_images");
                assertThat(row.action()).isEqualTo("docker image prune -af");
              });

      AgentActor.Ack ack =
          second
              .answerApproval(agent, prune(second, agent), false, "james", "still no")
              .toCompletableFuture()
              .get(20, TimeUnit.SECONDS);
      assertThat(ack.accepted()).isTrue();

      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> assertThat(stateOf(second, agent).phase()).isInstanceOf(Phase.Idle.class));

      var results = WatchmanPostgres.results(agent);
      assertThat(results).isNotEmpty();
      assertThat(results.values())
          .anySatisfy(text -> assertThat(text).contains("denied by james: still no"))
          .anySatisfy(text -> assertThat(text).contains("91% used"));

      // And once answered, it is off the page.
      assertThat(new PendingApprovals(WatchmanPostgres.dataSource(), Clock.systemUTC()).pending())
          .noneSatisfy(row -> assertThat(row.agentId()).isEqualTo(agent));
    } finally {
      second.stop();
    }
  }

  @Test
  void a_round_killed_mid_model_call_is_re_driven_by_the_sweep_with_nobody_asking() {
    String agent = "resume-" + UUID.randomUUID();

    WatchmanActorSystem first =
        WatchmanPostgres.start(new ScriptedWatchmanModel(Duration.ofSeconds(60)));
    try {
      first.tell(
          agent,
          new AgentActor.Observe("It is noon. Do your rounds.", "rounds", java.util.Map.of()));
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () ->
                  assertThat(stateOf(first, agent).phase()).isInstanceOf(Phase.CallingModel.class));
    } finally {
      first.stop();
    }

    WatchmanActorSystem second =
        WatchmanPostgres.start(new ScriptedWatchmanModel(Duration.ofMillis(20)));
    try {
      // Nothing is sent to this agent except the sweep's Wake. If the round moves, the sweep is
      // what moved it -- this is the driver obligation, and it is ours rather than Pekko's.
      List<String> unfinished = new StartupSweep(WatchmanPostgres.dataSource()).unfinishedAgents();
      assertThat(unfinished).contains(agent);
      unfinished.forEach(id -> second.tell(id, new AgentActor.Wake(java.util.Map.of())));

      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () ->
                  assertThat(stateOf(second, agent).phase())
                      .isInstanceOf(Phase.WorkingTools.class));
    } finally {
      second.stop();
    }
  }
}
