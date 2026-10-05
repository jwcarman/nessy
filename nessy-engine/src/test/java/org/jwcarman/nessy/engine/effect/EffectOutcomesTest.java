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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.PayloadRef;
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

/**
 * Each inference outcome becomes the command that carries the very manifest it came with: the
 * manifest is part of what the fold writes down, so a conversion that dropped or swapped it would
 * lose what a call was shown.
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
}
