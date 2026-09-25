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

package org.jwcarman.nessy.engine.history;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.spi.store.PayloadStore;

/**
 * What came back from the outside world, on its way into the fold.
 *
 * <p>An effect answers with content -- an answer, a tool result, the calls a model asked for. The
 * fold takes none of it: it branches on identifiers, status, decisions and counts, and everything
 * else is behind a reference. This is where one becomes the other, and it is the only place that
 * has to know both shapes.
 *
 * <p>The same translation whichever door is driving. A direct harness performs an effect on the
 * calling thread and a queued one picks it up from an outbox minutes later, and by the time either
 * has an outcome in hand the difference is over.
 */
public final class ClaimChecked {

  private final PayloadStore payloads;

  public ClaimChecked(PayloadStore payloads) {
    this.payloads = Objects.requireNonNull(payloads, "payloads must not be null");
  }

  /** The command this outcome becomes, with its content put away first. */
  public AgentCommand command(EffectOutcome outcome) {
    return switch (outcome) {
      case EffectOutcome.InferenceAnswered(List<Block.AnswerContent> blocks) ->
          new AgentCommand.CompleteInference(
              new AgentCommand.InferenceOutcome.Answered(payloads.put(blocks)));

      case EffectOutcome.InferenceRefused(String category) ->
          new AgentCommand.CompleteInference(new AgentCommand.InferenceOutcome.Refused(category));

      case EffectOutcome.InferenceFailed(var failure) ->
          new AgentCommand.CompleteInference(new AgentCommand.InferenceOutcome.Failed(failure));

      // The calls come out as their own list because the fold has to know what it is waiting for.
      // That is the one thing about a model's request it cannot take on trust from a reference.
      case EffectOutcome.InferenceRequestedActions(List<Block.ActionRequestContent> blocks) ->
          new AgentCommand.CompleteInference(
              new AgentCommand.InferenceOutcome.RequestedActions(
                  payloads.put(blocks), requested(blocks)));

      case EffectOutcome.ToolSucceeded(var callId, List<Block.ToolResultContent> blocks) ->
          new AgentCommand.CompleteToolCall(
              callId, new AgentCommand.ToolOutcome.Succeeded(payloads.put(blocks)));

      // A message rather than a reference: the model is going to read this, and a failure that
      // said only "see elsewhere" would be no use to it.
      case EffectOutcome.ToolFailed(var callId, String message) ->
          new AgentCommand.CompleteToolCall(callId, new AgentCommand.ToolOutcome.Failed(message));

      case EffectOutcome.ToolApproved(var callId, var reference) ->
          new AgentCommand.CompleteApproval(
              callId, new AgentCommand.ApprovalOutcome.Approved(reference));

      case EffectOutcome.ToolDenied(var callId, String reason, var reference) ->
          new AgentCommand.CompleteApproval(
              callId, new AgentCommand.ApprovalOutcome.Denied(reason, reference));
    };
  }

  /** Which calls a request obliges an outcome for, in the order the model made them. */
  public static List<AgentEvent.Requested> requested(List<Block.ActionRequestContent> blocks) {
    return blocks.stream()
        .filter(Block.ToolCall.class::isInstance)
        .map(Block.ToolCall.class::cast)
        .map(call -> new AgentEvent.Requested(call.id(), call.name()))
        .toList();
  }
}
