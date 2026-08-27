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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * THROWAWAY SPIKE. The behaviour contract, run against EVERY runtime.
 *
 * <p>This class is the round-3 thesis as an executable claim. Not one line of it knows whether the
 * agent it is talking to was found by {@link SpikeRegistry} on a single node or by Cluster Sharding
 * across a cluster — it only ever says "tell this agent id this command". Subclasses supply a
 * runtime; everything else is identical.
 *
 * <p>Ships in this module's test-jar so the cluster module can extend it rather than copy it. If
 * these tests pass in both modules, the same {@link AgentActor} genuinely runs under both lookups.
 */
@DisplayName("A Pekko-plumbed turn")
public abstract class SpikeFlowContract {

  protected static final Duration PATIENCE = Duration.ofSeconds(30);

  /** The one thing a subclass must provide. */
  protected abstract SpikeRuntime start(SpikeModel model, SpikeSweep sweep);

  private SpikeRuntime runtime;
  private String agent;

  @BeforeEach
  void startRuntime() {
    SpikeLifecycleLog.clear();
    agent = "flow-" + UUID.randomUUID();
    runtime = start(new ScriptedSpikeModel(Duration.ofMillis(50)), SpikeSweep.none());
  }

  @AfterEach
  void stopRuntime() {
    runtime.close();
  }

  protected final void tell(AgentActor.Command command) {
    runtime.agents().tell(agent, command);
  }

  protected final SpikeTurnState stateOf() {
    TestProbe<SpikeTurnState> probe = TestProbe.create(runtime.system());
    tell(new AgentActor.Inspect(probe.getRef()));
    return probe.receiveMessage(Duration.ofSeconds(15));
  }

  private void awaitState(Class<? extends SpikeTurnState> expected) {
    await().atMost(PATIENCE).untilAsserted(() -> assertThat(stateOf()).isInstanceOf(expected));
  }

  @Nested
  @DisplayName("Starting a turn")
  class StartingATurn {

    @Test
    void an_observation_drives_the_model_and_parks_on_the_call_that_needs_approval() {
      tell(new AgentActor.Observe("tidy up"));

      awaitState(SpikeTurnState.WorkingTools.class);

      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> {
                var working = (SpikeTurnState.WorkingTools) stateOf();
                assertThat(working.call("call-1")).isPresent();
                assertThat(working.call("call-1").orElseThrow().outcome()).isEqualTo("12:00");
                // The parked call has NO outcome, and that is the entire extent of what the
                // agent records about it. Where it stands is its own actor's business.
                assertThat(working.call("call-2").orElseThrow().settled()).isFalse();
              });
    }

    @Test
    void the_parked_turn_holds_no_thread_and_the_agent_can_be_let_go_entirely() {
      tell(new AgentActor.Observe("tidy up"));
      awaitState(SpikeTurnState.WorkingTools.class);

      tell(new AgentActor.Rest());

      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () ->
                  assertThat(SpikeLifecycleLog.notes())
                      .anyMatch(note -> note.startsWith(agent + ": stopped while working tools")));
    }
  }

  @Nested
  @DisplayName("Answering a parked approval")
  class AnsweringAParkedApproval {

    @Test
    void an_answer_rehydrates_the_agent_and_the_turn_runs_to_completion() {
      tell(new AgentActor.Observe("tidy up"));
      awaitState(SpikeTurnState.WorkingTools.class);
      tell(new AgentActor.Rest());
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () ->
                  assertThat(SpikeLifecycleLog.notes())
                      .anyMatch(note -> note.startsWith(agent + ": stopped while working tools")));

      // The agent id is the callback address. Nothing was recorded anywhere for this to work.
      tell(new AgentActor.AnswerApproval("call-2", true, ""));

      awaitState(SpikeTurnState.Idle.class);

      List<String> transcript = stateOf().transcript();
      assertThat(transcript).isNotEmpty();
      assertThat(transcript)
          .containsExactly(
              "user: tidy up",
              "assistant: (asked for [clock, delete])",
              "tool: clock -> 12:00",
              "tool: delete -> deleted /tmp/everything",
              "assistant: the clock says noon and the file is gone");

      assertThat(SpikeLifecycleLog.notes())
          .anyMatch(note -> note.startsWith(agent + ": rehydrated while working tools"));
    }

    @Test
    void a_denial_settles_the_call_without_running_the_tool() {
      tell(new AgentActor.Observe("tidy up"));
      awaitState(SpikeTurnState.WorkingTools.class);

      tell(new AgentActor.AnswerApproval("call-2", false, "absolutely not"));

      awaitState(SpikeTurnState.Idle.class);
      assertThat(stateOf().transcript()).contains("tool: delete -> denied: absolutely not");
    }

    @Test
    void a_duplicate_answer_changes_nothing() {
      tell(new AgentActor.Observe("tidy up"));
      awaitState(SpikeTurnState.WorkingTools.class);

      tell(new AgentActor.AnswerApproval("call-2", true, ""));
      tell(new AgentActor.AnswerApproval("call-2", false, "too late"));

      awaitState(SpikeTurnState.Idle.class);
      assertThat(stateOf().transcript())
          .contains("tool: delete -> deleted /tmp/everything")
          .doesNotContain("tool: delete -> denied: too late");
    }
  }
}
