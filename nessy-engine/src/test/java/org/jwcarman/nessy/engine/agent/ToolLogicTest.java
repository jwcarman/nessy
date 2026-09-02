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
import org.jwcarman.nessy.api.tool.ApprovalResult;

@DisplayName("What an agent does while its tools run")
class ToolLogicTest {

  private static AgentState working(Map<CallId, CallState> calls) {
    return AgentState.idle()
        .taking(TurnId.of("turn-1"), "claim-1")
        .at(new Phase.WorkingTools(calls));
  }

  @Nested
  class Approval {

    @Test
    void an_approved_call_runs() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of(CallId.of("a"), new CallState.Approving("send_email"))),
              new Input.ApprovalGiven(CallId.of("a"), "send_email", ApprovalResult.approved()));

      assertThat(decision.next().working().calls())
          .containsEntry(CallId.of("a"), new CallState.Running("send_email"));
      assertThat(decision.then()).contains(new Instruction.RunTool(CallId.of("a"), "send_email"));
    }

    @Test
    void a_denied_call_is_completed_without_ever_running() {
      Decision decision =
          AgentLogic.decide(
              working(
                  Map.of(
                      CallId.of("a"),
                      new CallState.Approving("send_email"),
                      CallId.of("b"),
                      new CallState.Running("read_file"))),
              new Input.ApprovalGiven(CallId.of("a"), "send_email", ApprovalResult.denied("no")));

      assertThat(decision.next().working().calls())
          .containsEntry(CallId.of("a"), new CallState.Completed());
      assertThat(decision.then()).isNotEmpty();
      assertThat(decision.then()).noneMatch(Instruction.RunTool.class::isInstance);
    }

    @Test
    void a_denial_does_not_end_the_turn() {
      Decision decision =
          AgentLogic.decide(
              working(
                  Map.of(
                      CallId.of("a"),
                      new CallState.Approving("send_email"),
                      CallId.of("b"),
                      new CallState.Running("read_file"))),
              new Input.ApprovalGiven(CallId.of("a"), "send_email", ApprovalResult.denied("no")));

      assertThat(decision.next().busy()).isTrue();
      assertThat(decision.then()).noneMatch(Instruction.Release.class::isInstance);
    }
  }

  @Nested
  class Parking {

    @Test
    void a_parked_call_arms_an_alarm_that_outlives_this_process() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of(CallId.of("a"), new CallState.Running("send_email"))),
              new Input.ToolParked(CallId.of("a"), java.time.Instant.EPOCH));

      assertThat(decision.next().working().calls())
          .containsEntry(CallId.of("a"), new CallState.Parked());
      assertThat(decision.then())
          .containsExactly(new Instruction.SetAlarm(CallId.of("a"), java.time.Instant.EPOCH));
    }

    @Test
    void an_answer_that_finally_arrives_disarms_it() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of(CallId.of("a"), new CallState.Parked())),
              new Input.ToolCompleted(CallId.of("a")));

      assertThat(decision.then()).contains(new Instruction.CancelAlarm(CallId.of("a")));
    }
  }

  @Nested
  class Finishing {

    @Test
    void the_last_call_completing_sends_the_exchange_back_to_the_model() {
      Decision decision =
          AgentLogic.decide(
              working(
                  Map.of(
                      CallId.of("a"),
                      new CallState.Completed(),
                      CallId.of("b"),
                      new CallState.Running("read_file"))),
              new Input.ToolCompleted(CallId.of("b")));

      assertThat(decision.next().phase()).isInstanceOf(Phase.CallingModel.class);
      assertThat(decision.then()).contains(new Instruction.CallModel());
    }

    @Test
    void one_call_completing_while_another_runs_changes_nothing_else() {
      Decision decision =
          AgentLogic.decide(
              working(
                  Map.of(
                      CallId.of("a"),
                      new CallState.Running("send_email"),
                      CallId.of("b"),
                      new CallState.Running("read_file"))),
              new Input.ToolCompleted(CallId.of("a")));

      assertThat(decision.next().working().calls())
          .containsEntry(CallId.of("b"), new CallState.Running("read_file"));
      assertThat(decision.then()).isNotEmpty();
      assertThat(decision.then()).noneMatch(Instruction.CallModel.class::isInstance);
    }

    @Test
    void a_deadline_completes_the_call_rather_than_ending_the_turn() {
      Decision decision =
          AgentLogic.decide(
              working(
                  Map.of(
                      CallId.of("a"),
                      new CallState.Parked(),
                      CallId.of("b"),
                      new CallState.Running("read_file"))),
              new Input.DeadlinePassed(CallId.of("a")));

      assertThat(decision.next().working().calls())
          .containsEntry(CallId.of("a"), new CallState.Completed());
      assertThat(decision.next().busy()).isTrue();
    }
  }
}
