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
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * What the fold does with the question an approval was decided on: it writes it on the event and
 * moves nothing else.
 *
 * <p>A separate file from {@code AgentStateTest} because the scenario builders there are private to
 * their nested groups. The state in each test is on turn 1 and its one request sits at seq 2, as
 * there.
 */
class AgentStateQuestionTest {

  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final PayloadRef MAIL = PayloadRef.of("payload-1");
  private static final PayloadRef QUESTION = PayloadRef.of("q1");
  private static final CallId CALL = new CallId("call-1");
  private static final ToolName TOOL = new ToolName("refund");
  private static final TurnId TURN = new TurnId(1);
  private static final Seq REQUEST = Seq.of(2);

  private static AgentState after(AgentState state, AgentCommand command) {
    Decision decision = state.execute(command);
    assertThat(decision).isInstanceOf(Decision.Advance.class);
    return state.applyAll(decision.events());
  }

  /** Seqs: 1 the turn opens, 2 the request; the one call awaits approval. */
  private static AgentState awaiting() {
    AgentState state = AgentState.idle(Seq.NONE);
    state =
        after(state, new AgentCommand.StartTurn(MAIL, "Question", Instant.EPOCH, Instant.EPOCH));
    return after(
        state,
        new AgentCommand.CompleteInference(
            TURN,
            new AgentCommand.InferenceOutcome.RequestedActions(
                MAIL,
                List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool", KEY)),
                Usage.unreported(),
                Optional.empty())));
  }

  private static AgentCommand.CompleteApproval approve(Optional<PayloadRef> question) {
    return new AgentCommand.CompleteApproval(
        TURN,
        REQUEST,
        CALL,
        new AgentCommand.ApprovalOutcome.Approved(Optional.of("ann"), question));
  }

  private static AgentCommand.CompleteApproval deny(Optional<PayloadRef> question) {
    return new AgentCommand.CompleteApproval(
        TURN,
        REQUEST,
        CALL,
        new AgentCommand.ApprovalOutcome.Denied("not today", Optional.of("ann"), question));
  }

  @Test
  void an_approval_is_recorded_with_the_question_it_was_decided_on() {
    Decision decision = awaiting().execute(approve(Optional.of(QUESTION)));

    assertThat(decision)
        .isEqualTo(
            Decision.of(
                List.of(
                    new AgentEvent.ToolApproved(
                        Seq.of(3), TURN, CALL, Optional.of("ann"), Optional.of(QUESTION), KEY)),
                List.of(new AgentEffect.CallTool(TURN, REQUEST, CALL, TOOL, KEY))));
  }

  @Test
  void a_denial_is_recorded_with_the_question_it_was_decided_on() {
    Decision decision = awaiting().execute(deny(Optional.of(QUESTION)));

    assertThat(decision)
        .isEqualTo(
            Decision.of(
                List.of(
                    new AgentEvent.ToolDenied(
                        Seq.of(3),
                        TURN,
                        CALL,
                        "not today",
                        Optional.of("ann"),
                        Optional.of(QUESTION),
                        KEY)),
                List.of(new AgentEffect.Infer(TURN))));
  }

  @Test
  void a_later_answer_carries_no_question() {
    Decision approved = awaiting().execute(approve(Optional.empty()));
    Decision denied = awaiting().execute(deny(Optional.empty()));

    assertThat(approved.events())
        .containsExactly(
            new AgentEvent.ToolApproved(
                Seq.of(3), TURN, CALL, Optional.of("ann"), Optional.empty(), KEY));
    assertThat(denied.events())
        .containsExactly(
            new AgentEvent.ToolDenied(
                Seq.of(3), TURN, CALL, "not today", Optional.of("ann"), Optional.empty(), KEY));
  }

  /** The question is a record of the decision, not an input to it. */
  @Test
  void the_question_changes_no_decision() {
    AgentState state = awaiting();

    Decision approvedWith = state.execute(approve(Optional.of(QUESTION)));
    Decision approvedWithout = state.execute(approve(Optional.empty()));
    Decision deniedWith = state.execute(deny(Optional.of(QUESTION)));
    Decision deniedWithout = state.execute(deny(Optional.empty()));

    assertThat(approvedWith.effects()).isNotEmpty().isEqualTo(approvedWithout.effects());
    assertThat(deniedWith.effects()).isNotEmpty().isEqualTo(deniedWithout.effects());
    assertThat(state.applyAll(approvedWith.events()))
        .isEqualTo(state.applyAll(approvedWithout.events()));
    assertThat(state.applyAll(deniedWith.events()))
        .isEqualTo(state.applyAll(deniedWithout.events()));
  }

  private static AgentCommand.CompleteToolCall fail(Optional<PayloadRef> question) {
    return new AgentCommand.CompleteToolCall(
        TURN,
        REQUEST,
        CALL,
        new AgentCommand.ToolOutcome.Failed(
            CallFailure.NOT_AUTHORISED, "the call could not be authorised: down", question));
  }

  /** A call that was running, so its failure is the tool's own. */
  private static AgentState running() {
    return after(awaiting(), approve(Optional.empty()));
  }

  @Test
  void a_failed_call_is_recorded_with_the_question_that_stood() {
    Decision decision = awaiting().execute(fail(Optional.of(QUESTION)));

    assertThat(decision)
        .isEqualTo(
            Decision.of(
                List.of(
                    new AgentEvent.ToolFailed(
                        Seq.of(3),
                        TURN,
                        CALL,
                        CallFailure.NOT_AUTHORISED,
                        "the call could not be authorised: down",
                        Optional.of(QUESTION),
                        KEY)),
                List.of(new AgentEffect.Infer(TURN))));
  }

  @Test
  void a_failed_call_with_no_question_records_none() {
    Decision asking = awaiting().execute(fail(Optional.empty()));
    Decision running = running().execute(fail(Optional.empty()));

    assertThat(asking.events())
        .containsExactly(
            new AgentEvent.ToolFailed(
                Seq.of(3),
                TURN,
                CALL,
                CallFailure.NOT_AUTHORISED,
                "the call could not be authorised: down",
                Optional.empty(),
                KEY));
    assertThat(running.events())
        .containsExactly(
            new AgentEvent.ToolFailed(
                Seq.of(4),
                TURN,
                CALL,
                CallFailure.NOT_AUTHORISED,
                "the call could not be authorised: down",
                Optional.empty(),
                KEY));
  }

  @Test
  void a_failure_of_a_running_call_records_the_question_it_was_given() {
    Decision decision = running().execute(fail(Optional.of(QUESTION)));

    assertThat(decision.events())
        .containsExactly(
            new AgentEvent.ToolFailed(
                Seq.of(4),
                TURN,
                CALL,
                CallFailure.NOT_AUTHORISED,
                "the call could not be authorised: down",
                Optional.of(QUESTION),
                KEY));
  }

  /** The question is a record of the failure, not an input to anything that follows it. */
  @Test
  void the_question_changes_no_decision_for_a_failure() {
    AgentState asking = awaiting();
    AgentState running = running();

    Decision askingWith = asking.execute(fail(Optional.of(QUESTION)));
    Decision askingWithout = asking.execute(fail(Optional.empty()));
    Decision runningWith = running.execute(fail(Optional.of(QUESTION)));
    Decision runningWithout = running.execute(fail(Optional.empty()));

    assertThat(askingWith.effects()).isNotEmpty().isEqualTo(askingWithout.effects());
    assertThat(runningWith.effects()).isNotEmpty().isEqualTo(runningWithout.effects());
    assertThat(asking.applyAll(askingWith.events()))
        .isEqualTo(asking.applyAll(askingWithout.events()));
    assertThat(running.applyAll(runningWith.events()))
        .isEqualTo(running.applyAll(runningWithout.events()));
  }
}
