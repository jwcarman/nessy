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
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the fold does with the facts an approval was decided on: it writes them on the event and
 * moves nothing else.
 *
 * <p>A separate file from {@code AgentStateTest} because the scenario builders there are private to
 * their nested groups. The state in each test is on turn 1 and its one request sits at seq 2, as
 * there.
 */
class AgentStateFactsTest {

  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final PayloadRef MAIL = PayloadRef.of("payload-1");
  private static final CallId CALL = new CallId("call-1");
  private static final ToolName TOOL = new ToolName("refund");
  private static final TurnId TURN = new TurnId(1);
  private static final Seq REQUEST = Seq.of(2);
  private static final Instant UNTIL = Instant.parse("2026-10-04T12:00:00Z");

  private static ObjectNode facts() {
    return JsonNodeFactory.instance.objectNode().put("risk", "low").put("depth", 2);
  }

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

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

  private static AgentCommand.CompleteApproval approve(ObjectNode facts) {
    return new AgentCommand.CompleteApproval(
        TURN, REQUEST, CALL, new AgentCommand.ApprovalOutcome.Approved(Optional.of("ann"), facts));
  }

  private static AgentCommand.CompleteApproval deny(ObjectNode facts) {
    return new AgentCommand.CompleteApproval(
        TURN,
        REQUEST,
        CALL,
        new AgentCommand.ApprovalOutcome.Denied("not today", Optional.of("ann"), facts));
  }

  private static AgentCommand.DeferApproval defer(ObjectNode facts) {
    return new AgentCommand.DeferApproval(TURN, REQUEST, CALL, UNTIL, facts);
  }

  @Test
  void an_approval_is_recorded_with_the_facts_it_was_decided_on() {
    Decision decision = awaiting().execute(approve(facts()));

    assertThat(decision)
        .isEqualTo(
            Decision.of(
                List.of(
                    new AgentEvent.ToolApproved(
                        Seq.of(3), TURN, CALL, Optional.of("ann"), facts(), KEY)),
                List.of(new AgentEffect.CallTool(TURN, REQUEST, CALL, TOOL, KEY))));
  }

  @Test
  void a_denial_is_recorded_with_the_facts_it_was_decided_on() {
    Decision decision = awaiting().execute(deny(facts()));

    assertThat(decision)
        .isEqualTo(
            Decision.of(
                List.of(
                    new AgentEvent.ToolDenied(
                        Seq.of(3), TURN, CALL, "not today", Optional.of("ann"), facts(), KEY)),
                List.of(new AgentEffect.Infer(TURN))));
  }

  @Test
  void a_decision_with_no_facts_records_an_empty_object() {
    Decision approved = awaiting().execute(approve(none()));
    Decision denied = awaiting().execute(deny(none()));

    assertThat(approved.events())
        .containsExactly(
            new AgentEvent.ToolApproved(Seq.of(3), TURN, CALL, Optional.of("ann"), none(), KEY));
    assertThat(denied.events())
        .containsExactly(
            new AgentEvent.ToolDenied(
                Seq.of(3), TURN, CALL, "not today", Optional.of("ann"), none(), KEY));
  }

  /** The facts are a record of the decision, not an input to it. */
  @Test
  void the_facts_change_no_decision() {
    AgentState state = awaiting();

    Decision approvedWith = state.execute(approve(facts()));
    Decision approvedWithout = state.execute(approve(none()));
    Decision deniedWith = state.execute(deny(facts()));
    Decision deniedWithout = state.execute(deny(none()));

    assertThat(approvedWith.effects()).isNotEmpty().isEqualTo(approvedWithout.effects());
    assertThat(deniedWith.effects()).isNotEmpty().isEqualTo(deniedWithout.effects());
    assertThat(state.applyAll(approvedWith.events()))
        .isEqualTo(state.applyAll(approvedWithout.events()));
    assertThat(state.applyAll(deniedWith.events()))
        .isEqualTo(state.applyAll(deniedWithout.events()));
  }

  private static AgentCommand.CompleteToolCall fail(ObjectNode facts) {
    return new AgentCommand.CompleteToolCall(
        TURN,
        REQUEST,
        CALL,
        new AgentCommand.ToolOutcome.Failed(
            CallFailure.NOT_AUTHORISED, "the call could not be authorised: down", facts));
  }

  /** A call that was running, so its failure is the tool's own. */
  private static AgentState running() {
    return after(awaiting(), approve(none()));
  }

  @Test
  void a_failed_call_is_recorded_with_the_facts_that_stood() {
    Decision decision = awaiting().execute(fail(facts()));

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
                        facts(),
                        KEY)),
                List.of(new AgentEffect.Infer(TURN))));
  }

  @Test
  void a_failed_call_with_no_facts_records_an_empty_object() {
    Decision asking = awaiting().execute(fail(none()));
    Decision running = running().execute(fail(none()));

    assertThat(asking.events())
        .containsExactly(
            new AgentEvent.ToolFailed(
                Seq.of(3),
                TURN,
                CALL,
                CallFailure.NOT_AUTHORISED,
                "the call could not be authorised: down",
                none(),
                KEY));
    assertThat(running.events())
        .containsExactly(
            new AgentEvent.ToolFailed(
                Seq.of(4),
                TURN,
                CALL,
                CallFailure.NOT_AUTHORISED,
                "the call could not be authorised: down",
                none(),
                KEY));
  }

  @Test
  void a_failure_of_a_running_call_records_the_facts_it_was_given() {
    Decision decision = running().execute(fail(facts()));

    assertThat(decision.events())
        .containsExactly(
            new AgentEvent.ToolFailed(
                Seq.of(4),
                TURN,
                CALL,
                CallFailure.NOT_AUTHORISED,
                "the call could not be authorised: down",
                facts(),
                KEY));
  }

  /** The facts are a record of the failure, not an input to anything that follows it. */
  @Test
  void the_facts_change_no_decision_for_a_failure() {
    AgentState asking = awaiting();
    AgentState running = running();

    Decision askingWith = asking.execute(fail(facts()));
    Decision askingWithout = asking.execute(fail(none()));
    Decision runningWith = running.execute(fail(facts()));
    Decision runningWithout = running.execute(fail(none()));

    assertThat(askingWith.effects()).isNotEmpty().isEqualTo(askingWithout.effects());
    assertThat(runningWith.effects()).isNotEmpty().isEqualTo(runningWithout.effects());
    assertThat(asking.applyAll(askingWith.events()))
        .isEqualTo(asking.applyAll(askingWithout.events()));
    assertThat(running.applyAll(runningWith.events()))
        .isEqualTo(running.applyAll(runningWithout.events()));
  }

  @Test
  void a_deferral_is_recorded_with_the_facts_it_was_put_aside_on() {
    Decision decision = awaiting().execute(defer(facts()));

    assertThat(decision.events())
        .containsExactly(
            new AgentEvent.ApprovalDeferred(Seq.of(3), TURN, CALL, UNTIL, facts(), KEY));
  }

  @Test
  void a_deferral_with_no_facts_records_an_empty_object() {
    Decision decision = awaiting().execute(defer(none()));

    assertThat(decision.events())
        .containsExactly(
            new AgentEvent.ApprovalDeferred(Seq.of(3), TURN, CALL, UNTIL, none(), KEY));
  }

  @Test
  void the_facts_change_no_decision_for_a_deferral() {
    AgentState state = awaiting();

    Decision with = state.execute(defer(facts()));
    Decision without = state.execute(defer(none()));

    assertThat(with.events()).isNotEmpty();
    assertThat(with.effects()).isEqualTo(without.effects());
    assertThat(state.applyAll(with.events())).isEqualTo(state.applyAll(without.events()));
  }
}
