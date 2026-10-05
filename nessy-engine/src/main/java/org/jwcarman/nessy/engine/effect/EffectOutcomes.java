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

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
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
 *
 * <p>The request is passed in for the same reason. A call id can repeat across two requests of one
 * turn, so the id and the turn do not say which request an answer is for; the effect does, and the
 * command cannot be built without it. The one answer that cannot name its request is the stored
 * failure of an effect row that would not decode, which cannot name its turn either.
 */
public final class EffectOutcomes {

  private EffectOutcomes() {}

  /**
   * Whether an outcome settles one call of a request, and so has to say which request it is
   * answering. An inference answers a turn and nothing smaller.
   */
  public static boolean answersARequest(EffectOutcome outcome) {
    return switch (outcome) {
      case EffectOutcome.InferenceAnswered _,
          EffectOutcome.InferenceRefused _,
          EffectOutcome.InferenceFailed _,
          EffectOutcome.InferenceRequestedActions _ ->
          false;
      case EffectOutcome.ToolSucceeded _,
          EffectOutcome.ToolFailed _,
          EffectOutcome.ToolApproved _,
          EffectOutcome.ToolDenied _ ->
          true;
    };
  }

  /**
   * The request an effect asked on behalf of, if it asked on behalf of one: where the actions
   * request that holds its call sits. A model call asks on behalf of none.
   */
  public static Optional<Seq> requestOf(AgentEffect effect) {
    return switch (effect) {
      case AgentEffect.CallTool call -> Optional.of(call.requestSeq());
      case AgentEffect.Approve approve -> Optional.of(approve.requestSeq());
      case AgentEffect.Infer _ -> Optional.empty();
    };
  }

  /**
   * An outcome, as the command it becomes. Nothing is unpacked: it already holds references.
   *
   * @param request the request the effect was asked on behalf of; present for every outcome that
   *     {@linkplain #answersARequest answers one}, and ignored for the rest
   * @throws IllegalArgumentException if the outcome answers a request and none is named
   */
  public static AgentCommand command(
      TurnId turn,
      Optional<Seq> request,
      EffectOutcome outcome,
      List<FailedAttempt> priorAttempts) {
    if (answersARequest(outcome) && request.isEmpty()) {
      throw new IllegalArgumentException(
          "%s answers a request and none was named".formatted(outcome.getClass().getSimpleName()));
    }
    return switch (outcome) {
      case EffectOutcome.InferenceAnswered(var answer, var truncated, var usage) ->
          new AgentCommand.CompleteInference(
              turn,
              new AgentCommand.InferenceOutcome.Answered(answer, truncated, usage),
              priorAttempts);
      case EffectOutcome.InferenceRefused(String category, var usage) ->
          new AgentCommand.CompleteInference(
              turn, new AgentCommand.InferenceOutcome.Refused(category, usage), priorAttempts);
      case EffectOutcome.InferenceFailed(var failure, var usage) ->
          new AgentCommand.CompleteInference(
              turn, new AgentCommand.InferenceOutcome.Failed(failure, usage), priorAttempts);
      case EffectOutcome.InferenceRequestedActions(var asked, var calls, var usage) ->
          new AgentCommand.CompleteInference(
              turn,
              new AgentCommand.InferenceOutcome.RequestedActions(asked, calls, usage),
              priorAttempts);
      case EffectOutcome.ToolSucceeded(var callId, var result, var rendered) ->
          new AgentCommand.CompleteToolCall(
              turn,
              request.get(),
              callId,
              new AgentCommand.ToolOutcome.Succeeded(result, rendered));
      case EffectOutcome.ToolFailed(var callId, var kind, String message) ->
          new AgentCommand.CompleteToolCall(
              turn, request.get(), callId, new AgentCommand.ToolOutcome.Failed(kind, message));
      case EffectOutcome.ToolApproved(var callId, var decidedBy, var question) ->
          new AgentCommand.CompleteApproval(
              turn,
              request.get(),
              callId,
              new AgentCommand.ApprovalOutcome.Approved(decidedBy, question));
      case EffectOutcome.ToolDenied(var callId, String reason, var decidedBy, var question) ->
          new AgentCommand.CompleteApproval(
              turn,
              request.get(),
              callId,
              new AgentCommand.ApprovalOutcome.Denied(reason, decidedBy, question));
    };
  }
}
