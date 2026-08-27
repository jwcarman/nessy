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
package org.jwcarman.nessy.spike.pekko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.typesafe.config.ConfigFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THROWAWAY SPIKE -- PHASE 2. THE TEST THAT MATTERS.
 *
 * <p>The identical entity code as phase 1, against Postgres instead of memory. The only difference
 * is the config file this test loads. If anything in {@code src/main/java} had to change to get
 * here, the "code to Pekko's API and let its modules own durability" principle would be false.
 *
 * <p>Requires the {@code watchman-postgres} container and the {@code pekko_spike} schema (see
 * {@code pekko-spike-schema.sql}). Tagged {@code container} so the default build -- which must stay
 * green with no Docker and no network -- skips it, following this repo's existing convention.
 */
@Tag("container")
@DisplayName("A parked turn, across a real JVM restart")
class PekkoSpikeRestartTest {

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private static SpikeCluster start() {
    return new SpikeCluster(ConfigFactory.load("spike-postgres").resolve(), Duration.ofMillis(50));
  }

  private static SpikeTurnState stateOf(SpikeCluster cluster, String agentId) {
    TestProbe<SpikeTurnState> probe = TestProbe.create(cluster.system());
    cluster.agent(agentId).tell(new SpikeTurnEntity.Inspect(probe.getRef()));
    return probe.receiveMessage(Duration.ofSeconds(15));
  }

  private static void awaitState(
      SpikeCluster cluster, String agentId, Class<? extends SpikeTurnState> expected) {
    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(stateOf(cluster, agentId)).isInstanceOf(expected));
  }

  @Test
  void a_turn_parked_on_an_approval_survives_a_full_actor_system_restart_and_completes() {
    String agent = "restart-" + UUID.randomUUID();
    SpikeLifecycleLog.clear();

    // ---- the first JVM lifetime -------------------------------------------------------------
    try (SpikeCluster first = start()) {
      first.agent(agent).tell(new SpikeTurnEntity.Observe("tidy up"));
      awaitState(first, agent, SpikeTurnState.WorkingTools.class);

      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> {
                var working = (SpikeTurnState.WorkingTools) stateOf(first, agent);
                assertThat(working.call("call-2")).isPresent();
                assertThat(working.call("call-2").orElseThrow().phase())
                    .isInstanceOf(SpikeCallPhase.AwaitingApproval.class);
              });
      // close() terminates the ActorSystem AND awaits termination -- a real kill, not a pause.
    }

    // ---- a genuinely new actor system, same Postgres ------------------------------------------
    try (SpikeCluster second = start()) {
      assertThat(stateOf(second, agent)).isInstanceOf(SpikeTurnState.WorkingTools.class);

      // The park's other end, arriving at a process that has never seen this turn before.
      second.agent(agent).tell(new SpikeTurnEntity.AnswerApproval("call-2", true, ""));

      awaitState(second, agent, SpikeTurnState.Idle.class);

      List<String> transcript = stateOf(second, agent).transcript();
      assertThat(transcript).isNotEmpty();
      assertThat(transcript)
          .containsExactly(
              "user: tidy up",
              "assistant: (asked for [clock, delete])",
              "tool: clock -> 12:00",
              "tool: delete -> deleted /tmp/everything",
              "assistant: the clock says noon and the file is gone");
    }
  }

  @Test
  void a_turn_killed_mid_model_call_re_issues_it_on_the_next_start_with_nobody_asking() {
    String agent = "resume-" + UUID.randomUUID();
    SpikeLifecycleLog.clear();

    // A model slow enough that we can kill the JVM while the call is genuinely in flight.
    try (SpikeCluster first =
        new SpikeCluster(ConfigFactory.load("spike-postgres").resolve(), Duration.ofSeconds(30))) {
      first.agent(agent).tell(new SpikeTurnEntity.Observe("tidy up"));
      awaitState(first, agent, SpikeTurnState.CallingModel.class);
    }

    // Nothing is sent to this agent below. If the turn moves, something other than a client
    // message moved it -- which is the whole "who drives a stalled turn" question.
    try (SpikeCluster second = start()) {
      // Poking the entity with Inspect would itself wake it, so wait on the lifecycle log first.
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () ->
                  assertThat(SpikeLifecycleLog.notes())
                      .anyMatch(
                          note -> note.equals(agent + ": rehydrated while calling the model")));

      awaitState(second, agent, SpikeTurnState.WorkingTools.class);
    }
  }
}
