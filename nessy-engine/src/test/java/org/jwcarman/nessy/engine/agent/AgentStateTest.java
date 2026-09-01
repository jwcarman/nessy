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
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("What an agent persists")
class AgentStateTest {

  @Nested
  class AtRest {

    @Test
    void an_idle_agent_is_working_on_nothing() {
      AgentState state = AgentState.idle();

      assertThat(state.busy()).isFalse();
      assertThat(state.phase()).isInstanceOf(Phase.Idle.class);
      assertThat(state.observation()).isNull();
    }

    @Test
    void taking_names_the_turn_and_its_claim_in_one_step() {
      AgentState state = AgentState.idle().taking("turn-1", "claim-1");

      assertThat(state.busy()).isTrue();
      assertThat(state.turnId()).isEqualTo("turn-1");
      assertThat(state.observation()).isEqualTo("claim-1");
      assertThat(state.phase()).isInstanceOf(Phase.CallingModel.class);
    }

    @Test
    void finishing_keeps_the_claim_id_because_the_next_take_must_name_it() {
      AgentState finished = AgentState.idle().taking("turn-1", "claim-1").finished();

      assertThat(finished.busy()).isFalse();
      assertThat(finished.observation())
          .as("the swept id, which the next take hands back to the store")
          .isEqualTo("claim-1");
    }

    @Test
    void asking_an_idle_agent_what_its_tools_are_doing_is_a_bug_rather_than_an_empty_answer() {
      AgentState idle = AgentState.idle();

      assertThatThrownBy(idle::working)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not working tools");
    }
  }

  @Nested
  class WorkingTools {

    @Test
    void a_phase_with_one_unsettled_call_is_not_finished() {
      Phase.WorkingTools working =
          new Phase.WorkingTools(
              Map.of("a", new CallState.Running("send_email"), "b", new CallState.Completed()));

      assertThat(working.calls()).isNotEmpty();
      assertThat(working.allSettled()).isFalse();
    }

    @Test
    void a_phase_whose_calls_have_all_completed_is_finished() {
      Phase.WorkingTools working = new Phase.WorkingTools(Map.of("a", new CallState.Completed()));

      assertThat(working.calls()).isNotEmpty();
      assertThat(working.allSettled()).isTrue();
    }

    @Test
    void replacing_one_call_leaves_the_others_alone() {
      Phase.WorkingTools working =
          new Phase.WorkingTools(
                  Map.of(
                      "a", new CallState.Approving("send_email"),
                      "b", new CallState.Running("read_file")))
              .with("a", new CallState.Completed());

      assertThat(working.calls())
          .containsEntry("a", new CallState.Completed())
          .containsEntry("b", new CallState.Running("read_file"));
    }
  }
}
