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

  @Nested
  @DisplayName("a call that has ended has no deadline, whichever way it ended")
  class Alarms {

    // A parked call armed an alarm. Every way that call can now end must disarm it, because
    // ReminderSweep RE-ARMS what it fires: a row that outlives its call wakes this agent about a
    // settled decision every backoff, forever. Measured on a live watchman -- an approval denied
    // at 11:00 still held an alarm for three days later.

    @Test
    @DisplayName("a denial from a desk cancels the alarm the park armed")
    void a_denied_call_disarms_its_alarm() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of(CallId.of("a"), new CallState.Parked())),
              new Input.ApprovalGiven(
                  CallId.of("a"), "prune_images", ApprovalResult.denied("not tonight")));

      assertThat(decision.then()).contains(new Instruction.CancelAlarm(CallId.of("a")));
    }

    @Test
    @DisplayName("a deadline that passes deletes its own row rather than being re-armed")
    void a_deadline_that_passes_disarms_its_alarm() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of(CallId.of("a"), new CallState.Parked())),
              new Input.DeadlinePassed(CallId.of("a")));

      assertThat(decision.then()).contains(new Instruction.CancelAlarm(CallId.of("a")));
    }

    @Test
    void a_completed_tool_still_cancels_as_it_always_did() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of(CallId.of("a"), new CallState.Running("send_email"))),
              new Input.ToolCompleted(CallId.of("a")));

      assertThat(decision.then()).contains(new Instruction.CancelAlarm(CallId.of("a")));
    }

    @Test
    @DisplayName("an approval does NOT cancel: the tool has not run yet and may take its time")
    void an_approved_call_keeps_its_alarm_until_the_tool_finishes() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of(CallId.of("a"), new CallState.Parked())),
              new Input.ApprovalGiven(CallId.of("a"), "long_job", ApprovalResult.approved()));

      assertThat(decision.then())
          .as("an approved call is still outstanding, so its deadline still means something")
          .doesNotContain(new Instruction.CancelAlarm(CallId.of("a")));
    }
  }
}
