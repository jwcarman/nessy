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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.agent.OutstandingAction;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the fold does with a deferral: one fact written, nothing else moved.
 *
 * <p>A separate file from {@code AgentStateTest} because the scenario builders there are private to
 * their nested groups, and the table walk needs one set of builders for every state. Every state in
 * this file is on turn 1 and its one request sits at seq 2, as there.
 */
class AgentStateDeferralTest {

  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final IdempotencyKey KEY_B =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000002"));
  private static final IdempotencyKey KEY_C =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000003"));

  private static final PayloadRef MAIL = PayloadRef.of("payload-1");
  private static final PayloadRef RESULT = PayloadRef.of("payload-3");
  private static final PayloadRef ANSWER = PayloadRef.of("payload-2");
  private static final Instant UNTIL = Instant.parse("2026-10-05T12:00:00Z");

  private static final CallId CALL = new CallId("call-1");
  private static final CallId A = new CallId("call-a");
  private static final CallId B = new CallId("call-b");
  private static final CallId C = new CallId("call-c");
  private static final ToolName TOOL = new ToolName("refund");

  private static final TurnId TURN = new TurnId(1);
  private static final Seq REQUEST = Seq.of(2);

  private static final TurnPolicy NEVER_ASKED =
      (stats, now) -> {
        throw new IllegalStateException("the turn policy was asked");
      };

  private static ObjectNode facts() {
    return JsonNodeFactory.instance.objectNode().put("risk", "low").put("depth", 2);
  }

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static AgentCommand.DeferApproval deferApproval(CallId call) {
    return new AgentCommand.DeferApproval(TURN, REQUEST, call, UNTIL, facts());
  }

  private static AgentCommand.DeferToolCall deferTool(CallId call) {
    return new AgentCommand.DeferToolCall(TURN, REQUEST, call, UNTIL);
  }

  private static AgentCommand.CompleteApproval approve(CallId call) {
    return new AgentCommand.CompleteApproval(
        TURN, REQUEST, call, new AgentCommand.ApprovalOutcome.Approved(Optional.empty(), none()));
  }

  private static AgentCommand.CompleteApproval deny(CallId call) {
    return new AgentCommand.CompleteApproval(
        TURN,
        REQUEST,
        call,
        new AgentCommand.ApprovalOutcome.Denied("not today", Optional.of("ann"), none()));
  }

  private static AgentCommand.CompleteToolCall succeed(CallId call) {
    return new AgentCommand.CompleteToolCall(
        TURN, REQUEST, call, new AgentCommand.ToolOutcome.Succeeded(RESULT, "refunded"));
  }

  private static AgentCommand.CompleteToolCall fail(CallId call, CallFailure kind) {
    return new AgentCommand.CompleteToolCall(
        TURN, REQUEST, call, new AgentCommand.ToolOutcome.Failed(kind, "it broke", none()));
  }

  private static AgentEvent.ApprovalDeferred approvalDeferred(
      int seq, CallId call, IdempotencyKey key) {
    return new AgentEvent.ApprovalDeferred(Seq.of(seq), TURN, call, UNTIL, facts(), key);
  }

  private static AgentEvent.ToolDeferred toolDeferred(int seq, CallId call, IdempotencyKey key) {
    return new AgentEvent.ToolDeferred(Seq.of(seq), TURN, call, UNTIL, key);
  }

  private static Decision advance(AgentEvent event) {
    return Decision.of(List.of(event), List.of());
  }

  /** The state after one command, written as a decision's events are. */
  private static AgentState after(AgentState state, AgentCommand command) {
    Decision decision = state.execute(command);
    assertThat(decision).isInstanceOf(Decision.Advance.class);
    return state.applyAll(decision.events());
  }

  /** Seqs: 1 the turn opens, 2 the request. The calls given all await approval. */
  private static AgentState awaiting(CallId... calls) {
    AgentState state = AgentState.idle(Seq.NONE);
    state =
        after(state, new AgentCommand.StartTurn(MAIL, "Question", Instant.EPOCH, Instant.EPOCH));
    List<ActionRequest> requests = new ArrayList<>();
    for (CallId call : calls) {
      requests.add(new ActionRequest.ToolCall(call, TOOL, "tool", keyOf(call)));
    }
    return after(
        state,
        new AgentCommand.CompleteInference(
            TURN,
            new AgentCommand.InferenceOutcome.RequestedActions(
                MAIL, requests, Usage.unreported(), Optional.empty())));
  }

  private static IdempotencyKey keyOf(CallId call) {
    if (call.equals(B)) {
      return KEY_B;
    }
    if (call.equals(C)) {
      return KEY_C;
    }
    return KEY;
  }

  private static AgentState.AwaitingActions awaitingActions(AgentState state) {
    assertThat(state).isInstanceOf(AgentState.AwaitingActions.class);
    return (AgentState.AwaitingActions) state;
  }

  @Nested
  @DisplayName("deferring")
  class Deferring {

    @Test
    void an_approval_that_is_deferred_is_recorded_and_nothing_else_happens() {
      AgentState state = awaiting(CALL);

      Decision decision = state.execute(deferApproval(CALL));

      assertThat(decision.events()).isEqualTo(List.of(approvalDeferred(3, CALL, KEY)));
      assertThat(decision.effects()).isEmpty();
      assertThat(decision).isEqualTo(advance(approvalDeferred(3, CALL, KEY)));
    }

    @Test
    void a_tool_call_that_is_deferred_is_recorded_and_nothing_else_happens() {
      AgentState state = after(awaiting(CALL), approve(CALL));

      Decision decision = state.execute(deferTool(CALL));

      assertThat(decision.events()).isEqualTo(List.of(toolDeferred(4, CALL, KEY)));
      assertThat(decision.effects()).isEmpty();
      assertThat(decision).isEqualTo(advance(toolDeferred(4, CALL, KEY)));
    }

    @Test
    void a_deferral_changes_no_outstanding_call() {
      AgentState.AwaitingActions approval = awaitingActions(awaiting(CALL));
      AgentState.AwaitingActions running = awaitingActions(after(approval, approve(CALL)));
      AgentEvent approvalEvent = approvalDeferred(3, CALL, KEY);
      AgentEvent toolEvent = toolDeferred(4, CALL, KEY);

      AgentState afterApproval = approval.apply(approvalEvent);
      AgentState afterTool = running.apply(toolEvent);

      assertThat(approval.outstanding())
          .isEqualTo(Map.of(CALL, OutstandingAction.awaitingApproval(CALL, TOOL, KEY, REQUEST)));
      assertThat(afterApproval)
          .isEqualTo(
              new AgentState.AwaitingActions(
                  Seq.of(3), TURN, REQUEST, approval.outstanding(), approval.stats()));
      assertThat(running.outstanding())
          .isEqualTo(
              Map.of(
                  CALL,
                  OutstandingAction.awaitingApproval(CALL, TOOL, KEY, REQUEST).running(Seq.of(3))));
      assertThat(afterTool)
          .isEqualTo(
              new AgentState.AwaitingActions(
                  Seq.of(4), TURN, REQUEST, running.outstanding(), running.stats()));
    }

    @Test
    void an_approval_deferred_for_another_turn_is_ignored() {
      AgentState state = awaiting(CALL);

      Decision decision =
          state.execute(
              new AgentCommand.DeferApproval(new TurnId(9), REQUEST, CALL, UNTIL, facts()));

      assertThat(decision).isEqualTo(Decision.ignore());
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    void an_approval_deferred_for_another_request_is_ignored() {
      AgentState state = awaiting(CALL);

      Decision decision =
          state.execute(new AgentCommand.DeferApproval(TURN, Seq.of(1), CALL, UNTIL, facts()));

      assertThat(decision).isEqualTo(Decision.ignore());
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    void an_approval_deferred_for_a_call_that_was_settled_is_ignored() {
      AgentState state = after(awaiting(A, B), deny(A));

      Decision decision = state.execute(deferApproval(A));

      assertThat(decision).isEqualTo(Decision.ignore());
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    void an_approval_deferred_for_a_call_that_is_already_running_is_ignored() {
      AgentState state = after(awaiting(CALL), approve(CALL));

      Decision decision = state.execute(deferApproval(CALL));

      assertThat(decision).isEqualTo(Decision.ignore());
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    void a_tool_deferral_for_another_turn_is_ignored() {
      AgentState state = after(awaiting(CALL), approve(CALL));

      Decision decision =
          state.execute(new AgentCommand.DeferToolCall(new TurnId(9), REQUEST, CALL, UNTIL));

      assertThat(decision).isEqualTo(Decision.ignore());
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    void a_tool_deferral_for_another_request_is_ignored() {
      AgentState state = after(awaiting(CALL), approve(CALL));

      Decision decision =
          state.execute(new AgentCommand.DeferToolCall(TURN, Seq.of(1), CALL, UNTIL));

      assertThat(decision).isEqualTo(Decision.ignore());
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    void a_tool_deferral_for_a_call_that_was_settled_is_ignored() {
      AgentState state = after(after(after(awaiting(A, B), approve(A)), approve(B)), succeed(A));

      Decision decision = state.execute(deferTool(A));

      assertThat(decision).isEqualTo(Decision.ignore());
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    void a_tool_deferral_for_a_call_still_awaiting_approval_is_ignored() {
      AgentState state = awaiting(CALL);

      Decision decision = state.execute(deferTool(CALL));

      assertThat(decision).isEqualTo(Decision.ignore());
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    void a_deferral_does_not_ask_the_turn_policy() {
      AgentState approval = awaiting(CALL);
      AgentState running = after(approval, approve(CALL));

      Decision deferredApproval = approval.execute(deferApproval(CALL), NEVER_ASKED, UNTIL);
      Decision deferredTool = running.execute(deferTool(CALL), NEVER_ASKED, UNTIL);

      assertThat(deferredApproval).isEqualTo(advance(approvalDeferred(3, CALL, KEY)));
      assertThat(deferredTool).isEqualTo(advance(toolDeferred(4, CALL, KEY)));
    }

    @Test
    void an_approval_after_its_deferral_runs_the_call_as_before() {
      AgentState plain = awaiting(CALL);
      AgentState deferred = after(plain, deferApproval(CALL));

      Decision without = plain.execute(approve(CALL));
      Decision with = deferred.execute(approve(CALL));

      AgentEffect callTool = new AgentEffect.CallTool(TURN, REQUEST, CALL, TOOL, KEY);
      assertThat(without)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolApproved(
                          Seq.of(3), TURN, CALL, Optional.empty(), none(), KEY)),
                  List.of(callTool)));
      assertThat(with)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolApproved(
                          Seq.of(4), TURN, CALL, Optional.empty(), none(), KEY)),
                  List.of(callTool)));
    }

    @Test
    void a_denial_after_its_deferral_ends_the_call_as_before() {
      AgentState plain = awaiting(CALL);
      AgentState deferred = after(plain, deferApproval(CALL));

      Decision without = plain.execute(deny(CALL));
      Decision with = deferred.execute(deny(CALL));

      assertThat(without)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolDenied(
                          Seq.of(3), TURN, CALL, "not today", Optional.of("ann"), none(), KEY)),
                  List.of(new AgentEffect.Infer(TURN))));
      assertThat(with)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolDenied(
                          Seq.of(4), TURN, CALL, "not today", Optional.of("ann"), none(), KEY)),
                  List.of(new AgentEffect.Infer(TURN))));
    }

    @Test
    void a_result_after_a_tool_deferral_ends_the_call_as_before() {
      AgentState plain = after(awaiting(CALL), approve(CALL));
      AgentState deferred = after(plain, deferTool(CALL));

      Decision without = plain.execute(succeed(CALL));
      Decision with = deferred.execute(succeed(CALL));

      assertThat(without)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolSucceeded(Seq.of(4), TURN, CALL, RESULT, "refunded", KEY)),
                  List.of(new AgentEffect.Infer(TURN))));
      assertThat(with)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolSucceeded(Seq.of(5), TURN, CALL, RESULT, "refunded", KEY)),
                  List.of(new AgentEffect.Infer(TURN))));
    }

    @Test
    void a_failure_after_a_deferral_ends_the_call_as_before() {
      AgentState plain = awaiting(CALL);
      AgentState deferred = after(plain, deferApproval(CALL));

      Decision without = plain.execute(fail(CALL, CallFailure.NOT_AUTHORISED));
      Decision with = deferred.execute(fail(CALL, CallFailure.NOT_AUTHORISED));

      assertThat(without)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolFailed(
                          Seq.of(3),
                          TURN,
                          CALL,
                          CallFailure.NOT_AUTHORISED,
                          "it broke",
                          none(),
                          KEY)),
                  List.of(new AgentEffect.Infer(TURN))));
      assertThat(with)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolFailed(
                          Seq.of(4),
                          TURN,
                          CALL,
                          CallFailure.NOT_AUTHORISED,
                          "it broke",
                          none(),
                          KEY)),
                  List.of(new AgentEffect.Infer(TURN))));
    }

    @Test
    void a_failure_after_a_tool_deferral_ends_the_call_as_before() {
      AgentState plain = after(awaiting(CALL), approve(CALL));
      AgentState deferred = after(plain, deferTool(CALL));

      Decision without = plain.execute(fail(CALL, CallFailure.FAILED));
      Decision with = deferred.execute(fail(CALL, CallFailure.FAILED));

      assertThat(without)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolFailed(
                          Seq.of(4), TURN, CALL, CallFailure.FAILED, "it broke", none(), KEY)),
                  List.of(new AgentEffect.Infer(TURN))));
      assertThat(with)
          .isEqualTo(
              Decision.of(
                  List.of(
                      new AgentEvent.ToolFailed(
                          Seq.of(5), TURN, CALL, CallFailure.FAILED, "it broke", none(), KEY)),
                  List.of(new AgentEffect.Infer(TURN))));
    }

    @Test
    void with_other_calls_outstanding_a_deferral_leaves_them_as_they_were() {
      // A awaits approval, B is running, C awaits approval. Seqs: 3 B approved.
      AgentState state = after(awaiting(A, B, C), approve(B));
      AgentState.AwaitingActions before = awaitingActions(state);
      assertThat(before.outstanding())
          .isEqualTo(
              Map.of(
                  A, OutstandingAction.awaitingApproval(A, TOOL, KEY, REQUEST),
                  B, OutstandingAction.awaitingApproval(B, TOOL, KEY_B, REQUEST).running(Seq.of(3)),
                  C, OutstandingAction.awaitingApproval(C, TOOL, KEY_C, REQUEST)));

      Decision deferredApproval = state.execute(deferApproval(A));
      Decision deferredTool = state.execute(deferTool(B));

      assertThat(deferredApproval).isEqualTo(advance(approvalDeferred(4, A, KEY)));
      assertThat(deferredTool).isEqualTo(advance(toolDeferred(4, B, KEY_B)));
      assertThat(state.applyAll(deferredApproval.events()))
          .isEqualTo(
              new AgentState.AwaitingActions(
                  Seq.of(4), TURN, REQUEST, before.outstanding(), before.stats()));
      assertThat(state.applyAll(deferredTool.events()))
          .isEqualTo(
              new AgentState.AwaitingActions(
                  Seq.of(4), TURN, REQUEST, before.outstanding(), before.stats()));
    }

    @Test
    @DisplayName("a command sent twice is written twice, because the fold does not remember it")
    void the_same_deferral_twice_is_recorded_twice() {
      AgentState.AwaitingActions start = awaitingActions(awaiting(CALL));
      AgentState once = after(start, deferApproval(CALL));

      Decision again = once.execute(deferApproval(CALL));

      assertThat(again).isEqualTo(advance(approvalDeferred(4, CALL, KEY)));
      assertThat(once.applyAll(again.events()))
          .isEqualTo(
              new AgentState.AwaitingActions(
                  Seq.of(4), TURN, REQUEST, start.outstanding(), start.stats()));
    }
  }

  @Nested
  @DisplayName("the whole table")
  class Table {

    private record Row(
        String label,
        AgentState state,
        AgentCommand deferApproval,
        Decision approvalDecision,
        AgentCommand deferTool,
        Decision toolDecision) {}

    private final TurnStats stats = TurnStats.opened(Instant.EPOCH);

    private AgentState.AwaitingActions holding(OutstandingAction call) {
      return new AgentState.AwaitingActions(Seq.of(2), TURN, REQUEST, Map.of(CALL, call), stats);
    }

    private final OutstandingAction waiting =
        OutstandingAction.awaitingApproval(CALL, TOOL, KEY, REQUEST);

    private Row ignoredBoth(String label, AgentState state) {
      return new Row(
          label, state, deferApproval(CALL), Decision.ignore(), deferTool(CALL), Decision.ignore());
    }

    private List<Row> rows() {
      return List.of(
          ignoredBoth("Idle", new AgentState.Idle(Seq.of(5))),
          ignoredBoth("Inferring", new AgentState.Inferring(Seq.of(1), TURN, stats)),
          ignoredBoth("Terminal", new AgentState.Terminal()),
          new Row(
              "AwaitingActions, another turn",
              holding(waiting),
              new AgentCommand.DeferApproval(new TurnId(9), REQUEST, CALL, UNTIL, facts()),
              Decision.ignore(),
              new AgentCommand.DeferToolCall(new TurnId(9), REQUEST, CALL, UNTIL),
              Decision.ignore()),
          new Row(
              "AwaitingActions, another request",
              holding(waiting),
              new AgentCommand.DeferApproval(TURN, Seq.of(1), CALL, UNTIL, facts()),
              Decision.ignore(),
              new AgentCommand.DeferToolCall(TURN, Seq.of(1), CALL, UNTIL),
              Decision.ignore()),
          new Row(
              "AwaitingActions, the call is not outstanding",
              holding(waiting),
              deferApproval(new CallId("call-zzz")),
              Decision.ignore(),
              deferTool(new CallId("call-zzz")),
              Decision.ignore()),
          new Row(
              "AwaitingActions, the call is AWAITING_APPROVAL",
              holding(waiting),
              deferApproval(CALL),
              advance(approvalDeferred(3, CALL, KEY)),
              deferTool(CALL),
              Decision.ignore()),
          new Row(
              "AwaitingActions, the call is RUNNING",
              holding(waiting.running(Seq.of(2))),
              deferApproval(CALL),
              Decision.ignore(),
              deferTool(CALL),
              advance(toolDeferred(3, CALL, KEY))));
    }

    @Test
    void every_state_against_both_deferral_commands() {
      List<Row> rows = rows();

      assertThat(rows).hasSize(8);
      for (Row row : rows) {
        assertThat(row.state().execute(row.deferApproval()))
            .as(row.label() + " against DeferApproval")
            .isEqualTo(row.approvalDecision());
        assertThat(row.state().execute(row.deferTool()))
            .as(row.label() + " against DeferToolCall")
            .isEqualTo(row.toolDecision());
      }
    }

    @Test
    void every_state_against_both_deferral_events() {
      AgentEvent approval = approvalDeferred(6, CALL, KEY);
      AgentEvent tool = toolDeferred(6, CALL, KEY);
      AgentState.AwaitingActions waitingState = holding(waiting);
      AgentState.AwaitingActions runningState = holding(waiting.running(Seq.of(2)));
      AgentState idleState = new AgentState.Idle(Seq.of(5));
      AgentState inferring = new AgentState.Inferring(Seq.of(5), TURN, stats);
      AgentState terminal = new AgentState.Terminal();

      assertThat(waitingState.apply(approval))
          .isEqualTo(
              new AgentState.AwaitingActions(
                  Seq.of(6), TURN, REQUEST, waitingState.outstanding(), stats));
      assertThat(runningState.apply(tool))
          .isEqualTo(
              new AgentState.AwaitingActions(
                  Seq.of(6), TURN, REQUEST, runningState.outstanding(), stats));
      for (AgentState state : List.of(idleState, inferring)) {
        assertThatThrownBy(() -> state.apply(approval))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("ApprovalDeferred cannot happen in " + state.getClass().getSimpleName());
        assertThatThrownBy(() -> state.apply(tool))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("ToolDeferred cannot happen in " + state.getClass().getSimpleName());
      }
      assertThatThrownBy(() -> terminal.apply(approval))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("a terminated agent accepts nothing further: " + approval);
      assertThatThrownBy(() -> terminal.apply(tool))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("a terminated agent accepts nothing further: " + tool);
    }
  }

  @Nested
  @DisplayName("replay")
  class Replay {

    /** A state kept live, command by command, with every event it wrote in order. */
    private static final class Run {
      private AgentState state = AgentState.idle(Seq.NONE);
      private final List<AgentEvent> written = new ArrayList<>();

      Run step(AgentCommand command) {
        Decision decision = state.execute(command);
        assertThat(decision).isInstanceOf(Decision.Advance.class);
        written.addAll(decision.events());
        state = state.applyAll(decision.events());
        return this;
      }

      Run opened(CallId... calls) {
        step(new AgentCommand.StartTurn(MAIL, "Question", Instant.EPOCH, Instant.EPOCH));
        List<ActionRequest> requests = new ArrayList<>();
        for (CallId call : calls) {
          requests.add(new ActionRequest.ToolCall(call, TOOL, "tool", keyOf(call)));
        }
        return step(
            new AgentCommand.CompleteInference(
                TURN,
                new AgentCommand.InferenceOutcome.RequestedActions(
                    MAIL, requests, Usage.unreported(), Optional.empty())));
      }

      void assertReplayEqualsLive(int events, Class<? extends AgentState> ending) {
        assertThat(written).hasSize(events);
        assertThat(state).isInstanceOf(ending);
        assertThat(AgentState.idle(Seq.NONE).applyAll(written)).isEqualTo(state);
      }
    }

    @Test
    void a_deferred_approval_then_approved_replays_to_the_state_that_wrote_it() {
      Run run = new Run().opened(CALL).step(deferApproval(CALL)).step(approve(CALL));

      run.assertReplayEqualsLive(4, AgentState.AwaitingActions.class);
    }

    @Test
    void a_deferred_approval_then_denied_replays_to_the_state_that_wrote_it() {
      Run run = new Run().opened(CALL).step(deferApproval(CALL)).step(deny(CALL));

      run.assertReplayEqualsLive(4, AgentState.Inferring.class);
    }

    @Test
    void a_deferred_approval_that_expires_into_a_failure_replays_to_the_state_that_wrote_it() {
      Run run =
          new Run()
              .opened(CALL)
              .step(deferApproval(CALL))
              .step(fail(CALL, CallFailure.NOT_AUTHORISED))
              .step(
                  new AgentCommand.CompleteInference(
                      TURN,
                      new AgentCommand.InferenceOutcome.Answered(
                          ANSWER, false, Usage.unreported(), Optional.empty())));

      run.assertReplayEqualsLive(5, AgentState.Idle.class);
    }

    @Test
    void a_deferred_tool_then_its_result_replays_to_the_state_that_wrote_it() {
      Run run =
          new Run().opened(CALL).step(approve(CALL)).step(deferTool(CALL)).step(succeed(CALL));

      run.assertReplayEqualsLive(5, AgentState.Inferring.class);
    }

    @Test
    void a_deferred_tool_then_its_failure_replays_to_the_state_that_wrote_it() {
      Run run =
          new Run()
              .opened(CALL)
              .step(approve(CALL))
              .step(deferTool(CALL))
              .step(fail(CALL, CallFailure.FAILED));

      run.assertReplayEqualsLive(5, AgentState.Inferring.class);
    }

    @Test
    void two_calls_with_one_deferred_replay_to_the_state_that_wrote_them() {
      Run run =
          new Run()
              .opened(A, B)
              .step(deferApproval(A))
              .step(approve(B))
              .step(deferTool(B))
              .step(approve(A));

      run.assertReplayEqualsLive(6, AgentState.AwaitingActions.class);
    }

    @Test
    void a_deferral_written_twice_replays_to_the_state_that_wrote_it() {
      Run run = new Run().opened(CALL).step(deferApproval(CALL)).step(deferApproval(CALL));

      run.assertReplayEqualsLive(4, AgentState.AwaitingActions.class);
    }
  }
}
