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
      assertThat(decision.then()).contains(new Effect.RunTool(CallId.of("a"), "send_email"));
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
      assertThat(decision.then()).noneMatch(Effect.RunTool.class::isInstance);
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
      assertThat(decision.then()).noneMatch(Effect.Release.class::isInstance);
    }
  }

  @Nested
  class Parking {

    @Test
    @DisplayName("a parked call only moves its own state -- arming its deadline is the shell's job")
    void a_parked_call_names_no_effect_of_its_own() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of(CallId.of("a"), new CallState.Running("send_email"))),
              new Input.ToolParked(CallId.of("a"), java.time.Instant.EPOCH));

      assertThat(decision.next().working().calls())
          .containsEntry(CallId.of("a"), new CallState.Parked());
      assertThat(decision.then()).isEmpty();
    }

    @Test
    void an_answer_that_finally_arrives_completes_the_call() {
      // A second call still running, so the turn does not end and decision.next() is still
      // WorkingTools to inspect -- the same reason Finishing's own tests pair a call with another.
      Decision decision =
          AgentLogic.decide(
              working(
                  Map.of(
                      CallId.of("a"),
                      new CallState.Parked(),
                      CallId.of("b"),
                      new CallState.Running("read_file"))),
              new Input.ToolCompleted(CallId.of("a")));

      assertThat(decision.next().working().calls())
          .containsEntry(CallId.of("a"), new CallState.Completed());
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
      assertThat(decision.then()).contains(new Effect.CallModel());
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
      assertThat(decision.then()).noneMatch(Effect.CallModel.class::isInstance);
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

  /**
   * A call can be answered twice, and the second answer must not be fatal.
   *
   * <p>The engine parks a call with a TERM and denies it on the human's behalf when that term
   * expires. A person answering just after the deadline fired is therefore ORDINARY, not exotic —
   * and it was killing the agent. Measured on a live watchman: the reminder sweep settled a call at
   * 18:45:06, a denial arrived from the page at 18:45:26, and the actor stopped with "not working
   * tools: CallingModel[]" because the last settle had already moved the turn on.
   *
   * <p>The same shape as a duplicate WorkTaken: an answer about something this agent is no longer
   * waiting on is news it has already had.
   */
  @Nested
  @DisplayName("an answer that arrives too late")
  class LateAnswers {

    @Test
    @DisplayName("a denial for a call the deadline already settled is ignored")
    void a_second_answer_does_not_kill_the_agent() {
      AgentState movedOn =
          working(Map.of(CallId.of("a"), new CallState.Completed())).at(new Phase.CallingModel());

      Decision decision =
          AgentLogic.decide(
              movedOn,
              new Input.ApprovalGiven(CallId.of("a"), "prune_images", ApprovalResult.denied("no")));

      assertThat(decision.next()).isEqualTo(movedOn);
      assertThat(decision.then()).isEmpty();
    }

    @Test
    @DisplayName("a deadline for a call that already completed is ignored")
    void a_late_deadline_is_ignored() {
      AgentState movedOn =
          working(Map.of(CallId.of("a"), new CallState.Completed())).at(new Phase.CallingModel());

      Decision decision = AgentLogic.decide(movedOn, new Input.DeadlinePassed(CallId.of("a")));

      assertThat(decision.next()).isEqualTo(movedOn);
      assertThat(decision.then()).isEmpty();
    }

    @Test
    @DisplayName("an answer naming a call this turn never had is ignored")
    void an_answer_for_an_unknown_call_is_ignored() {
      AgentState state = working(Map.of(CallId.of("a"), new CallState.Approving("send_email")));

      Decision decision =
          AgentLogic.decide(
              state,
              new Input.ApprovalGiven(
                  CallId.of("stranger"), "send_email", ApprovalResult.approved()));

      assertThat(decision.next()).isEqualTo(state);
      assertThat(decision.then()).isEmpty();
    }
  }
}
