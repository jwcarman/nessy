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
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
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

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StoryEventsTest {

  private static final Seq SEQ = new Seq(7);
  private static final TurnId TURN = new TurnId(3);
  private static final CallId CALL = new CallId("c1");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  @Test
  void a_turn_starting_is_told_as_turn_started() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.TurnStarted(SEQ, TURN, PayloadRef.of("p"), Instant.EPOCH)))
        .containsExactly(new Narration.TurnStarted(TURN));
  }

  @Test
  void an_answer_is_told_as_answered_and_then_the_turn_ending() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.InferenceAnswered(
                    SEQ, TURN, PayloadRef.of("p"), Usage.unreported())))
        .containsExactly(new Narration.Answered(), new Narration.TurnEnded(TURN));
  }

  @Test
  void a_retried_model_call_is_not_told() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.InferenceAttempted(
                    SEQ, TURN, new Failure.Transient("busy"), Usage.unreported())))
        .isEmpty();
  }

  @Test
  void requested_actions_are_told_with_each_calls_id_tool_and_action() {
    AgentEvent.ActionsRequested asked =
        new AgentEvent.ActionsRequested(
            SEQ,
            TURN,
            PayloadRef.of("p"),
            List.of(new ActionRequest.ToolCall(CALL, new ToolName("lookup"), "look it up", KEY)),
            Usage.unreported());

    assertThat(StoryEvents.of(asked))
        .containsExactly(
            new Narration.ActionsRequested(
                List.of(
                    new Narration.ActionsRequested.Call(
                        CALL, new ToolName("lookup"), "look it up"))));
  }

  @Test
  void a_refused_turn_is_told_as_refused_and_then_the_turn_ending() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.InferenceRefused(SEQ, TURN, "policy", Usage.unreported())))
        .containsExactly(new Narration.TurnRefused("policy"), new Narration.TurnEnded(TURN));
  }

  @Test
  void a_failed_model_call_is_told_with_its_reason_and_then_the_turn_ending() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.InferenceFailed(
                    SEQ, TURN, new Failure.Permanent("broken"), Usage.unreported())))
        .containsExactly(new Narration.TurnFailed("broken"), new Narration.TurnEnded(TURN));
  }

  @Test
  void a_turn_a_policy_ended_is_told_with_its_reason_and_then_the_turn_ending() {
    assertThat(StoryEvents.of(new AgentEvent.TurnFailed(SEQ, TURN, "too many calls")))
        .containsExactly(new Narration.TurnFailed("too many calls"), new Narration.TurnEnded(TURN));
  }

  @Test
  void an_approved_call_is_told_as_approved() {
    assertThat(StoryEvents.of(new AgentEvent.ToolApproved(SEQ, TURN, CALL, Optional.empty())))
        .containsExactly(new Narration.CallApproved(CALL));
  }

  @Test
  void a_denied_call_is_told_with_the_reason() {
    assertThat(
            StoryEvents.of(
                new AgentEvent.ToolDenied(SEQ, TURN, CALL, "not allowed", Optional.empty())))
        .containsExactly(new Narration.CallDenied(CALL, "not allowed"));
  }

  @Test
  void a_call_that_succeeded_is_told_as_finished() {
    assertThat(
            StoryEvents.of(new AgentEvent.ToolSucceeded(SEQ, TURN, CALL, PayloadRef.of("r"), "ok")))
        .containsExactly(new Narration.CallFinished(CALL));
  }

  @Test
  void a_call_that_failed_is_told_with_the_message() {
    assertThat(StoryEvents.of(new AgentEvent.ToolFailed(SEQ, TURN, CALL, "boom")))
        .containsExactly(new Narration.CallFailed(CALL, "boom"));
  }

  @Test
  void termination_is_told_as_terminated() {
    assertThat(StoryEvents.of(new AgentEvent.Terminated(SEQ)))
        .containsExactly(new Narration.Terminated());
  }
}
