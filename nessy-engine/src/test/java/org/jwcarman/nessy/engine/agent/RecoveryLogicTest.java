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

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;

@DisplayName("What an agent does when it wakes up")
class RecoveryLogicTest {

  private static AgentState working(Map<CallId, CallState> calls) {
    return AgentState.idle()
        .taking(TurnId.of("turn-1"), "claim-1")
        .at(new Phase.WorkingTools(calls));
  }

  private static Decision recovering(Map<CallId, CallState> calls) {
    return AgentLogic.decide(working(calls), new Input.Recovered());
  }

  @Nested
  class Rested {

    @Test
    void an_idle_agent_asks_whether_anything_is_waiting() {
      Decision decision = AgentLogic.decide(AgentState.idle(), new Input.Recovered());

      assertThat(decision.then()).containsExactly(new Instruction.TakeWork());
    }
  }

  @Nested
  class MidTurn {

    @Test
    void a_turn_that_died_calling_the_model_calls_it_again() {
      AgentState calling = AgentState.idle().taking(TurnId.of("turn-1"), "claim-1");

      Decision decision = AgentLogic.decide(calling, new Input.Recovered());

      assertThat(decision.then()).containsExactly(new Instruction.CallModel());
    }

    @Test
    void recovery_never_changes_the_state_it_recovered() {
      AgentState before = working(Map.of(CallId.of("a"), new CallState.Running("read_file")));

      assertThat(AgentLogic.decide(before, new Input.Recovered()).next()).isEqualTo(before);
    }

    @Test
    void a_call_that_died_being_approved_is_asked_again_because_asking_is_idempotent() {
      Decision decision = recovering(Map.of(CallId.of("a"), new CallState.Approving("send_email")));

      assertThat(decision.then())
          .containsExactly(new Instruction.AskApprover(CallId.of("a"), "send_email"));
    }

    @Test
    void a_call_that_died_running_runs_again_because_nobody_else_will_answer() {
      Decision decision = recovering(Map.of(CallId.of("a"), new CallState.Running("read_file")));

      assertThat(decision.then())
          .containsExactly(new Instruction.RunTool(CallId.of("a"), "read_file"));
    }

    @Test
    void a_parked_call_is_left_alone_because_re_asking_mints_a_second_reply_token() {
      Decision decision = recovering(Map.of(CallId.of("a"), new CallState.Parked()));

      assertThat(decision.then()).isEmpty();
    }

    @Test
    void a_completed_call_is_not_redone_because_its_result_is_in_claims() {
      Decision decision = recovering(Map.of(CallId.of("a"), new CallState.Completed()));

      assertThat(decision.then()).isEmpty();
    }

    @Test
    void a_mixed_turn_resumes_only_the_two_that_nobody_else_will_answer() {
      Decision decision =
          recovering(
              Map.of(
                  CallId.of("a"),
                  new CallState.Approving("send_email"),
                  CallId.of("b"),
                  new CallState.Running("read_file"),
                  CallId.of("c"),
                  new CallState.Parked(),
                  CallId.of("d"),
                  new CallState.Completed()));

      assertThat(decision.then())
          .containsExactlyInAnyOrder(
              new Instruction.AskApprover(CallId.of("a"), "send_email"),
              new Instruction.RunTool(CallId.of("b"), "read_file"));
    }
  }
}
