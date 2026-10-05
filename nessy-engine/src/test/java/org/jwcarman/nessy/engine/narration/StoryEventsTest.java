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
package org.jwcarman.nessy.engine.narration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.FailureKind;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.inference.Failure;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StoryEventsTest {

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static final Seq SEQ = new Seq(7);
  private static final TurnId TURN = new TurnId(3);
  private static final CallId CALL = new CallId("c1");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  @Test
  void a_turn_starting_is_told_as_turn_started() {
    Instant arrived = Instant.parse("2026-03-04T05:06:07Z");

    assertThat(
            StoryEvents.of(
                new AgentEvent.TurnStarted(
                    SEQ, TURN, PayloadRef.of("p"), "Invoice", arrived, Instant.EPOCH)))
        .isEqualTo(new Narration.TurnStarted(TURN, "Invoice", arrived));
  }

  @Test
  void an_answer_ends_its_turn_and_says_what_the_call_cost() {
    Usage usage = Usage.of("a-model", 100, 20);

    assertThat(
            StoryEvents.of(
                new AgentEvent.InferenceAnswered(
                    SEQ, TURN, PayloadRef.of("p"), true, usage, Optional.empty())))
        .isEqualTo(new Narration.Answered(TURN, true, usage));
  }

  @Test
  void a_retried_model_call_is_told_with_its_kind_and_cost() {
    Usage usage = Usage.of("a-model", 100, 0);

    assertThat(
            StoryEvents.of(
                new AgentEvent.InferenceAttempted(
                    SEQ, TURN, new Failure.Unknown("no answer"), usage, Optional.empty())))
        .isEqualTo(new Narration.InferenceRetried(TURN, FailureKind.UNKNOWN, "no answer", usage));
  }

  @Test
  void requested_actions_are_told_with_the_usage_and_each_calls_id_key_tool_and_action() {
    Usage usage = Usage.of("a-model", 10, 5);
    AgentEvent.ActionsRequested asked =
        new AgentEvent.ActionsRequested(
            SEQ,
            TURN,
            PayloadRef.of("p"),
            List.of(new ActionRequest.ToolCall(CALL, new ToolName("lookup"), "look it up", KEY)),
            usage,
            Optional.empty());

    assertThat(StoryEvents.of(asked))
        .isEqualTo(
            new Narration.ActionsRequested(
                TURN,
                List.of(
                    new Narration.ActionsRequested.Call(
                        CALL, KEY, new ToolName("lookup"), "look it up")),
                usage));
  }

  @Test
  void a_refused_turn_is_told_with_the_category_and_cost() {
    Usage usage = Usage.of("a-model", 7, 0);

    assertThat(
            StoryEvents.of(
                new AgentEvent.InferenceRefused(SEQ, TURN, "policy", usage, Optional.empty())))
        .isEqualTo(new Narration.TurnRefused(TURN, "policy", usage));
  }

  @Test
  void a_transient_failure_ends_the_turn_as_a_transient_failure() {
    assertFailureTold(new Failure.Transient("busy"), FailureKind.TRANSIENT, "busy");
  }

  @Test
  void an_unknown_failure_ends_the_turn_as_an_unknown_failure() {
    assertFailureTold(new Failure.Unknown("silence"), FailureKind.UNKNOWN, "silence");
  }

  @Test
  void a_permanent_failure_ends_the_turn_as_a_permanent_failure() {
    assertFailureTold(new Failure.Permanent("broken"), FailureKind.PERMANENT, "broken");
  }

  @Test
  void a_rejected_failure_ends_the_turn_as_a_rejected_failure() {
    assertFailureTold(new Failure.Rejected("bad input"), FailureKind.REJECTED, "bad input");
  }

  @Test
  void a_turn_a_policy_stopped_is_not_a_failed_model_call() {
    assertThat(StoryEvents.of(new AgentEvent.TurnStopped(SEQ, TURN, "too many calls")))
        .isEqualTo(new Narration.TurnStopped(TURN, "too many calls"));
  }

  @Test
  void the_events_supplied_here_cover_every_kind_the_store_can_hold() {
    Set<Class<?>> supplied =
        everyKind().<Class<?>>map(AgentEvent::getClass).collect(Collectors.toSet());
    Set<Class<?>> permitted = Set.of(AgentEvent.class.getPermittedSubclasses());

    assertThat(permitted).isNotEmpty();
    assertThat(supplied).isEqualTo(permitted);
  }

  @ParameterizedTest
  @MethodSource("everyKind")
  void every_supplied_kind_can_be_told(AgentEvent event) {
    assertThat(StoryEvents.of(event)).isNotNull();
  }

  static Stream<AgentEvent> everyKind() {
    Usage usage = Usage.unreported();
    return Stream.of(
        new AgentEvent.TurnStarted(
            SEQ, TURN, PayloadRef.of("p"), "Invoice", Instant.EPOCH, Instant.EPOCH),
        new AgentEvent.InferenceAnswered(
            SEQ, TURN, PayloadRef.of("p"), false, usage, Optional.empty()),
        new AgentEvent.InferenceRefused(SEQ, TURN, "policy", usage, Optional.empty()),
        new AgentEvent.InferenceFailed(
            SEQ, TURN, new Failure.Permanent("x"), usage, Optional.empty()),
        new AgentEvent.InferenceAttempted(
            SEQ, TURN, new Failure.Transient("x"), usage, Optional.empty()),
        new AgentEvent.TurnStopped(SEQ, TURN, "x"),
        new AgentEvent.ActionsRequested(
            SEQ, TURN, PayloadRef.of("p"), List.of(), usage, Optional.empty()),
        new AgentEvent.ToolApproved(SEQ, TURN, CALL, Optional.empty(), none(), KEY),
        new AgentEvent.ToolDenied(SEQ, TURN, CALL, "no", Optional.empty(), none(), KEY),
        new AgentEvent.ToolSucceeded(SEQ, TURN, CALL, PayloadRef.of("r"), "ok", KEY),
        new AgentEvent.ToolFailed(SEQ, TURN, CALL, CallFailure.FAILED, "boom", none(), KEY),
        new AgentEvent.ApprovalDeferred(SEQ, TURN, CALL, Instant.EPOCH, none(), KEY),
        new AgentEvent.ToolDeferred(SEQ, TURN, CALL, Instant.EPOCH, KEY),
        new AgentEvent.Terminated(SEQ));
  }

  private static void assertFailureTold(Failure failure, FailureKind kind, String reason) {
    Usage usage = Usage.of("a-model", 1, 1);

    assertThat(
            StoryEvents.of(
                new AgentEvent.InferenceFailed(SEQ, TURN, failure, usage, Optional.empty())))
        .isEqualTo(new Narration.TurnFailed(TURN, kind, reason, usage));
  }

  @Test
  void an_approved_call_is_told_as_approved() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.ToolApproved(SEQ, TURN, CALL, Optional.of("u_carol"), none(), KEY)))
        .isEqualTo(new Narration.CallApproved(CALL, KEY, Optional.of("u_carol")));
  }

  @Test
  void a_deferred_approval_is_told_with_its_call_its_key_and_its_deadline() {
    Instant until = Instant.parse("2026-10-05T09:30:00Z");

    assertThat(
            StoryEvents.of(
                new AgentEvent.ApprovalDeferred(
                    SEQ, TURN, CALL, until, none().put("risk", "low"), KEY)))
        .isEqualTo(new Narration.ApprovalDeferred(CALL, KEY, until));
  }

  @Test
  void a_deferred_tool_call_is_told_with_its_call_its_key_and_its_deadline() {
    Instant until = Instant.parse("2026-10-05T09:30:00Z");

    assertThat(StoryEvents.of(new AgentEvent.ToolDeferred(SEQ, TURN, CALL, until, KEY)))
        .isEqualTo(new Narration.CallDeferred(CALL, KEY, until));
  }

  @Test
  void a_denied_call_is_told_with_the_reason() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.ToolDenied(
                    SEQ, TURN, CALL, "not allowed", Optional.of("u_dave"), none(), KEY)))
        .isEqualTo(new Narration.CallDenied(CALL, KEY, "not allowed", Optional.of("u_dave")));
  }

  @Test
  void a_call_that_succeeded_is_told_as_finished() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.ToolSucceeded(SEQ, TURN, CALL, PayloadRef.of("r"), "ok", KEY)))
        .isEqualTo(new Narration.CallFinished(CALL, KEY));
  }

  @Test
  void a_call_that_failed_is_told_with_the_message() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.ToolFailed(
                    SEQ, TURN, CALL, CallFailure.FAILED, "boom", none(), KEY)))
        .isEqualTo(new Narration.CallFailed(CALL, KEY, CallFailure.FAILED, "boom"));
  }

  @Test
  void a_call_that_failed_is_told_with_why_it_failed() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.ToolFailed(
                    SEQ,
                    TURN,
                    CALL,
                    CallFailure.NOT_AUTHORISED,
                    "no approver answered",
                    none(),
                    KEY)))
        .isEqualTo(
            new Narration.CallFailed(
                CALL, KEY, CallFailure.NOT_AUTHORISED, "no approver answered"));
  }

  @Test
  void termination_is_told_as_terminated() {
    assertThat(StoryEvents.of(new AgentEvent.Terminated(SEQ)))
        .isEqualTo(new Narration.Terminated());
  }
}
