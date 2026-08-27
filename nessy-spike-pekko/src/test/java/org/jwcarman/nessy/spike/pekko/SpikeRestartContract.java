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

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THROWAWAY SPIKE. THE TEST THAT MATTERS, run against EVERY runtime.
 *
 * <p>Requires the {@code watchman-postgres} container and the {@code pekko_spike} schema. Tagged
 * {@code container} so the default build — which must stay green with no Docker and no network —
 * skips it, following this repo's existing convention.
 */
@Tag("container")
@DisplayName("A parked turn, across a real JVM restart")
public abstract class SpikeRestartContract {

  protected static final Duration PATIENCE = Duration.ofSeconds(45);

  /** The one thing a subclass must provide. */
  protected abstract SpikeRuntime start(SpikeModel model, SpikeSweep sweep);

  /** The sweep this tier uses to recreate unfinished turns, if it needs one. */
  protected abstract SpikeSweep sweep();

  private static SpikeTurnState stateOf(SpikeRuntime runtime, String agent) {
    TestProbe<SpikeTurnState> probe = TestProbe.create(runtime.system());
    runtime.agents().tell(agent, new AgentActor.Inspect(probe.getRef()));
    return probe.receiveMessage(Duration.ofSeconds(20));
  }

  private void awaitState(
      SpikeRuntime runtime, String agent, Class<? extends SpikeTurnState> expected) {
    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(stateOf(runtime, agent)).isInstanceOf(expected));
  }

  @Test
  void a_turn_parked_on_an_approval_survives_a_full_actor_system_restart_and_completes() {
    String agent = "restart-" + UUID.randomUUID();
    SpikeLifecycleLog.clear();

    try (SpikeRuntime first = start(new ScriptedSpikeModel(Duration.ofMillis(50)), sweep())) {
      first.agents().tell(agent, new AgentActor.Observe("tidy up"));
      awaitState(first, agent, SpikeTurnState.WorkingTools.class);
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> {
                var working = (SpikeTurnState.WorkingTools) stateOf(first, agent);
                assertThat(working.call("call-2")).isPresent();
                assertThat(working.call("call-2").orElseThrow().settled()).isFalse();
              });
      // close() terminates the ActorSystem AND awaits it -- a real kill, not a pause.
    }

    try (SpikeRuntime second = start(new ScriptedSpikeModel(Duration.ofMillis(50)), sweep())) {
      assertThat(stateOf(second, agent)).isInstanceOf(SpikeTurnState.WorkingTools.class);

      second.agents().tell(agent, new AgentActor.AnswerApproval("call-2", true, ""));

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

  /**
   * The driver obligation. Nothing is sent to this agent in the second lifetime — if the turn
   * moves, something other than a client message moved it. In the single-node tier that something
   * is {@link SpikeSweep}; in the sharded tier it is {@code rememberEntities}.
   */
  @Test
  void a_turn_killed_mid_model_call_is_re_driven_on_the_next_start_with_nobody_asking() {
    String agent = "resume-" + UUID.randomUUID();
    SpikeLifecycleLog.clear();

    try (SpikeRuntime first = start(new ScriptedSpikeModel(Duration.ofSeconds(60)), sweep())) {
      first.agents().tell(agent, new AgentActor.Observe("tidy up"));
      awaitState(first, agent, SpikeTurnState.CallingModel.class);
    }

    try (SpikeRuntime second = start(new ScriptedSpikeModel(Duration.ofMillis(50)), sweep())) {
      // Wait on the lifecycle log rather than on Inspect, because Inspect would itself wake it.
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
