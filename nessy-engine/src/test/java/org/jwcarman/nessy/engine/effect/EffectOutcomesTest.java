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
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.inference.Manifests;
import org.jwcarman.nessy.inference.Failure;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Each inference outcome becomes the command that carries the very manifest it came with: the
 * manifest is part of what the fold writes down, so a conversion that dropped or swapped it would
 * lose what a call was shown. A decision or a failure carries the facts the approver was shown into
 * its command unchanged.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EffectOutcomesTest {

  private static final TurnId TURN = new TurnId(7);
  private static final PayloadRef REPLY = new PayloadRef("a".repeat(64));
  private static final Usage USAGE = Usage.of("a-model", 11, 5);
  private static final List<ActionRequest> CALLS =
      List.of(
          new ActionRequest.ToolCall(
              new CallId("c1"),
              new ToolName("lookup"),
              "look it up",
              IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"))));

  static Stream<Arguments> inferenceArms() {
    Failure busy = new Failure.Transient("busy");
    return Stream.of(
        Arguments.of(
            "answered",
            new EffectOutcome.InferenceAnswered(
                REPLY, true, USAGE, Optional.of(Manifests.numbered(1))),
            new AgentCommand.CompleteInference(
                TURN,
                new AgentCommand.InferenceOutcome.Answered(
                    REPLY, true, USAGE, Optional.of(Manifests.numbered(1))),
                List.of())),
        Arguments.of(
            "refused",
            new EffectOutcome.InferenceRefused("safety", USAGE, Optional.of(Manifests.numbered(2))),
            new AgentCommand.CompleteInference(
                TURN,
                new AgentCommand.InferenceOutcome.Refused(
                    "safety", USAGE, Optional.of(Manifests.numbered(2))),
                List.of())),
        Arguments.of(
            "failed",
            new EffectOutcome.InferenceFailed(busy, USAGE, Optional.of(Manifests.numbered(3))),
            new AgentCommand.CompleteInference(
                TURN,
                new AgentCommand.InferenceOutcome.Failed(
                    busy, USAGE, Optional.of(Manifests.numbered(3))),
                List.of())),
        Arguments.of(
            "requested actions",
            new EffectOutcome.InferenceRequestedActions(
                REPLY, CALLS, USAGE, Optional.of(Manifests.numbered(4))),
            new AgentCommand.CompleteInference(
                TURN,
                new AgentCommand.InferenceOutcome.RequestedActions(
                    REPLY, CALLS, USAGE, Optional.of(Manifests.numbered(4))),
                List.of())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inferenceArms")
  void an_inference_outcome_becomes_the_command_that_carries_its_manifest(
      String arm, EffectOutcome outcome, AgentCommand expected) {
    AgentCommand command = EffectOutcomes.command(TURN, Optional.empty(), outcome, List.of());

    assertThat(command).as(arm).isEqualTo(expected);
  }

  private static final Seq REQUEST = new Seq(2);
  private static final CallId CALL = new CallId("c1");

  private static ObjectNode facts() {
    return JsonNodeFactory.instance.objectNode().put("risk", "low").put("depth", 2);
  }

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  @Test
  void an_approval_carries_its_facts_into_the_command() {
    EffectOutcome outcome = new EffectOutcome.ToolApproved(CALL, Optional.of("ann"), facts());

    AgentCommand command = EffectOutcomes.command(TURN, Optional.of(REQUEST), outcome, List.of());

    assertThat(command)
        .isEqualTo(
            new AgentCommand.CompleteApproval(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ApprovalOutcome.Approved(Optional.of("ann"), facts())));
  }

  @Test
  void a_denial_carries_its_facts_into_the_command() {
    EffectOutcome outcome = new EffectOutcome.ToolDenied(CALL, "no", Optional.of("ann"), facts());

    AgentCommand command = EffectOutcomes.command(TURN, Optional.of(REQUEST), outcome, List.of());

    assertThat(command)
        .isEqualTo(
            new AgentCommand.CompleteApproval(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ApprovalOutcome.Denied("no", Optional.of("ann"), facts())));
  }

  @Test
  void a_decision_without_facts_becomes_a_command_with_empty_facts() {
    AgentCommand approved =
        EffectOutcomes.command(
            TURN, Optional.of(REQUEST), new EffectOutcome.ToolApproved(CALL), List.of());

    assertThat(approved)
        .isEqualTo(
            new AgentCommand.CompleteApproval(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ApprovalOutcome.Approved(Optional.empty(), none())));
  }

  @Test
  void a_failure_carries_its_facts_into_the_command() {
    EffectOutcome outcome =
        new EffectOutcome.ToolFailed(
            CALL, CallFailure.NOT_AUTHORISED, "the call could not be authorised: down", facts());

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
                    facts())));
  }

  @Test
  void a_failure_without_facts_becomes_a_command_with_empty_facts() {
    EffectOutcome outcome = new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, "broke");

    AgentCommand command = EffectOutcomes.command(TURN, Optional.of(REQUEST), outcome, List.of());

    assertThat(command)
        .isEqualTo(
            new AgentCommand.CompleteToolCall(
                TURN,
                REQUEST,
                CALL,
                new AgentCommand.ToolOutcome.Failed(CallFailure.FAILED, "broke", none())));
  }
}
