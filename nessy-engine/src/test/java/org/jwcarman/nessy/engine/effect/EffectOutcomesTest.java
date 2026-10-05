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
package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.engine.core.AgentCommand;

/** What an outcome becomes as a command: the decision, and the question it was made on. */
class EffectOutcomesTest {

  private static final TurnId TURN = new TurnId(1);
  private static final Seq REQUEST = new Seq(2);
  private static final CallId CALL = new CallId("c1");
  private static final PayloadRef QUESTION = PayloadRef.of("q1");

  @Test
  void an_approval_carries_its_question_into_the_command() {
    EffectOutcome outcome =
        new EffectOutcome.ToolApproved(CALL, Optional.of("ann"), Optional.of(QUESTION));

    AgentCommand command = EffectOutcomes.command(TURN, Optional.of(REQUEST), outcome, List.of());

    assertThat(command)
        .isEqualTo(
            new AgentCommand.CompleteApproval(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ApprovalOutcome.Approved(
                    Optional.of("ann"), Optional.of(QUESTION))));
  }

  @Test
  void a_denial_carries_its_question_into_the_command() {
    EffectOutcome outcome =
        new EffectOutcome.ToolDenied(CALL, "no", Optional.of("ann"), Optional.of(QUESTION));

    AgentCommand command = EffectOutcomes.command(TURN, Optional.of(REQUEST), outcome, List.of());

    assertThat(command)
        .isEqualTo(
            new AgentCommand.CompleteApproval(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ApprovalOutcome.Denied(
                    "no", Optional.of("ann"), Optional.of(QUESTION))));
  }

  @Test
  void a_decision_without_a_question_becomes_a_command_without_one() {
    AgentCommand approved =
        EffectOutcomes.command(
            TURN, Optional.of(REQUEST), new EffectOutcome.ToolApproved(CALL), List.of());

    assertThat(approved)
        .isEqualTo(
            new AgentCommand.CompleteApproval(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ApprovalOutcome.Approved(Optional.empty(), Optional.empty())));
  }

  @Test
  void a_failure_carries_its_question_into_the_command() {
    EffectOutcome outcome =
        new EffectOutcome.ToolFailed(
            CALL,
            CallFailure.NOT_AUTHORISED,
            "the call could not be authorised: down",
            Optional.of(QUESTION));

    AgentCommand command = EffectOutcomes.command(TURN, Optional.of(REQUEST), outcome, List.of());

    assertThat(command)
        .isEqualTo(
            new AgentCommand.CompleteToolCall(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ToolOutcome.Failed(
                    CallFailure.NOT_AUTHORISED,
                    "the call could not be authorised: down",
                    Optional.of(QUESTION))));
  }

  @Test
  void a_failure_without_a_question_becomes_a_command_without_one() {
    EffectOutcome outcome = new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, "broke");

    AgentCommand command = EffectOutcomes.command(TURN, Optional.of(REQUEST), outcome, List.of());

    assertThat(command)
        .isEqualTo(
            new AgentCommand.CompleteToolCall(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ToolOutcome.Failed(
                    CallFailure.FAILED, "broke", Optional.empty())));
  }
}
