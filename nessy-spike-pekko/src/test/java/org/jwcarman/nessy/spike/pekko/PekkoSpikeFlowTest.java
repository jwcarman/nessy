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
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * THROWAWAY SPIKE -- PHASE 1. The flow, with no Postgres, no Docker and nothing external: the
 * durable-state store and the remember-entities journal are pekko-persistence-testkit's in-memory
 * plugins, chosen entirely in {@code spike-inmemory.conf}.
 */
@DisplayName("A Pekko-plumbed turn, in memory")
class PekkoSpikeFlowTest {

  private static final String AGENT = "agent-flow";

  private SpikeCluster cluster;

  @BeforeEach
  void start() {
    SpikeLifecycleLog.clear();
    cluster =
        new SpikeCluster(ConfigFactory.load("spike-inmemory").resolve(), Duration.ofMillis(50));
  }

  @AfterEach
  void stop() {
    cluster.close();
  }

  private SpikeTurnState stateOf(String agentId) {
    ActorSystem<Void> system = cluster.system();
    TestProbe<SpikeTurnState> probe = TestProbe.create(system);
    cluster.agent(agentId).tell(new SpikeTurnEntity.Inspect(probe.getRef()));
    return probe.receiveMessage(Duration.ofSeconds(10));
  }

  private void awaitState(String agentId, Class<? extends SpikeTurnState> expected) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(stateOf(agentId)).isInstanceOf(expected));
  }

  @Nested
  @DisplayName("Starting a turn")
  class StartingATurn {

    @Test
    void an_observation_drives_the_model_and_parks_on_the_call_that_needs_approval() {
      cluster.agent(AGENT).tell(new SpikeTurnEntity.Observe("tidy up"));

      awaitState(AGENT, SpikeTurnState.WorkingTools.class);

      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> {
                var working = (SpikeTurnState.WorkingTools) stateOf(AGENT);
                assertThat(working.call("call-1")).isPresent();
                assertThat(working.call("call-1").orElseThrow().phase())
                    .isInstanceOf(SpikeCallPhase.Finished.class);
                assertThat(working.call("call-2").orElseThrow().phase())
                    .isInstanceOf(SpikeCallPhase.AwaitingApproval.class);
              });
    }

    @Test
    void the_parked_turn_holds_no_thread_and_the_entity_can_be_let_go_entirely() {
      cluster.agent(AGENT).tell(new SpikeTurnEntity.Observe("tidy up"));
      awaitState(AGENT, SpikeTurnState.WorkingTools.class);

      // A park with a live actor proves nothing. Ask the shard to passivate: the actor really
      // stops, and the only thing left is a row in the durable-state store.
      cluster.agent(AGENT).tell(new SpikeTurnEntity.Rest());

      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(SpikeLifecycleLog.notes())
                      .anyMatch(note -> note.startsWith(AGENT + ": stopped while working tools")));
    }
  }

  @Nested
  @DisplayName("Answering a parked approval")
  class AnsweringAParkedApproval {

    @Test
    void an_answer_rehydrates_the_entity_and_the_turn_runs_to_completion() {
      cluster.agent(AGENT).tell(new SpikeTurnEntity.Observe("tidy up"));
      awaitState(AGENT, SpikeTurnState.WorkingTools.class);
      cluster.agent(AGENT).tell(new SpikeTurnEntity.Rest());
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(SpikeLifecycleLog.notes())
                      .anyMatch(note -> note.startsWith(AGENT + ": stopped while working tools")));

      // The whole point: the entity id is the callback address. Nothing was recorded anywhere
      // for this message to find its way home.
      cluster.agent(AGENT).tell(new SpikeTurnEntity.AnswerApproval("call-2", true, ""));

      awaitState(AGENT, SpikeTurnState.Idle.class);

      List<String> transcript = stateOf(AGENT).transcript();
      assertThat(transcript).isNotEmpty();
      assertThat(transcript)
          .containsExactly(
              "user: tidy up",
              "assistant: (asked for [clock, delete])",
              "tool: clock -> 12:00",
              "tool: delete -> deleted /tmp/everything",
              "assistant: the clock says noon and the file is gone");

      assertThat(SpikeLifecycleLog.notes())
          .anyMatch(note -> note.startsWith(AGENT + ": rehydrated while working tools"));
    }

    @Test
    void a_denial_settles_the_call_without_running_the_tool() {
      String agent = "agent-denial";
      cluster.agent(agent).tell(new SpikeTurnEntity.Observe("tidy up"));
      awaitState(agent, SpikeTurnState.WorkingTools.class);

      cluster
          .agent(agent)
          .tell(new SpikeTurnEntity.AnswerApproval("call-2", false, "absolutely not"));

      awaitState(agent, SpikeTurnState.Idle.class);
      assertThat(stateOf(agent).transcript()).contains("tool: delete -> denied: absolutely not");
    }

    @Test
    void a_duplicate_answer_changes_nothing() {
      String agent = "agent-duplicate";
      cluster.agent(agent).tell(new SpikeTurnEntity.Observe("tidy up"));
      awaitState(agent, SpikeTurnState.WorkingTools.class);

      cluster.agent(agent).tell(new SpikeTurnEntity.AnswerApproval("call-2", true, ""));
      cluster.agent(agent).tell(new SpikeTurnEntity.AnswerApproval("call-2", false, "too late"));

      awaitState(agent, SpikeTurnState.Idle.class);
      assertThat(stateOf(agent).transcript())
          .contains("tool: delete -> deleted /tmp/everything")
          .doesNotContain("tool: delete -> denied: too late");
    }
  }
}
