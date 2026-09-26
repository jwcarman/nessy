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

import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.core.AgentCommand;

/**
 * Turns what an effect came to into the command that tells an {@link
 * org.jwcarman.nessy.engine.core.AgentState} about it.
 *
 * <p>One conversion rather than two. Both doors discharge effects the same way once they have an
 * outcome in hand -- the queued door's {@code deliverOutcome} and the direct door's {@code within}
 * arms alike -- so there is exactly one place that says which {@link AgentCommand} an {@link
 * EffectOutcome} becomes.
 *
 * <p>The turn is passed in rather than read off the outcome, because an outcome says what happened
 * and not what asked for it. Whoever holds the effect knows which turn emitted it -- the direct
 * door from the effect it is performing, the queued door from the effect row it decoded -- and the
 * command cannot be built without it, which is what stops a late answer being folded into a turn it
 * has nothing to do with.
 */
public final class EffectOutcomes {

  private EffectOutcomes() {}

  /** An outcome, as the command it becomes. Nothing is unpacked: it already holds references. */
  public static AgentCommand command(TurnId turn, EffectOutcome outcome) {
    return switch (outcome) {
      case EffectOutcome.InferenceAnswered(var answer) ->
          new AgentCommand.CompleteInference(
              turn, new AgentCommand.InferenceOutcome.Answered(answer));
      case EffectOutcome.InferenceRefused(String category) ->
          new AgentCommand.CompleteInference(
              turn, new AgentCommand.InferenceOutcome.Refused(category));
      case EffectOutcome.InferenceFailed(var failure) ->
          new AgentCommand.CompleteInference(
              turn, new AgentCommand.InferenceOutcome.Failed(failure));
      case EffectOutcome.InferenceRequestedActions(var request, var calls) ->
          new AgentCommand.CompleteInference(
              turn, new AgentCommand.InferenceOutcome.RequestedActions(request, calls));
      case EffectOutcome.ToolSucceeded(var callId, var result) ->
          new AgentCommand.CompleteToolCall(
              turn, callId, new AgentCommand.ToolOutcome.Succeeded(result));
      case EffectOutcome.ToolFailed(var callId, String message) ->
          new AgentCommand.CompleteToolCall(
              turn, callId, new AgentCommand.ToolOutcome.Failed(message));
      case EffectOutcome.ToolApproved(var callId, var reference) ->
          new AgentCommand.CompleteApproval(
              turn, callId, new AgentCommand.ApprovalOutcome.Approved(reference));
      case EffectOutcome.ToolDenied(var callId, String reason, var reference) ->
          new AgentCommand.CompleteApproval(
              turn, callId, new AgentCommand.ApprovalOutcome.Denied(reason, reference));
    };
  }
}
