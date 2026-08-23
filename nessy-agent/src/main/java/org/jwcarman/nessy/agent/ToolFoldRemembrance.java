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
package org.jwcarman.nessy.agent;

import java.util.List;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;

/**
 * The tool-delivery fold moment, shared (remembrance spec §2): every non-ignored {@code
 * ToolFinished} remembers its own {@link Remembrance.ToolExchange}, keyed by the call's execution
 * {@code ComputationId} ({@link CallAddress#execution()}) — deterministic from {@code (agentType,
 * agentId, responseId, callId)}, so a redelivery re-remembers the same key and converges (SPI law
 * 2). When this is the call that completes the whole batch (the phase's {@link Transition} also
 * commits the deferred assistant turn alongside the tool-results message), the {@link
 * Remembrance.AssistantMessage} is remembered too, exactly once, keyed by the same response id.
 *
 * <p>Both {@link DefaultAgent} (the immediate, non-durable fold — most tool calls) and {@link
 * DeliveryWorker} (the durable, outbox-driven fold — deferred computations and approval grants)
 * fold a {@code ToolFinished} the same way; this is the one place that mapping lives.
 */
final class ToolFoldRemembrance {

  private ToolFoldRemembrance() {}

  /**
   * @param priorPhase the phase the (non-ignored) transition folded FROM — always {@link
   *     Phase.AwaitingTools}, the only phase a real {@code ToolFinished} transition can come from
   */
  static void remember(
      Memory memory,
      AgentType type,
      AgentId id,
      Phase priorPhase,
      ToolCall call,
      ToolOutcome outcome,
      Transition transition) {
    if (!(priorPhase instanceof Phase.AwaitingTools awaiting)) {
      throw new IllegalStateException(
          "a non-ignored ToolFinished transition folded from a phase other than AwaitingTools: "
              + priorPhase);
    }
    CallAddress address =
        new CallAddress(type.name(), id.value(), awaiting.responseId().value(), call.id());
    memory.remember(
        new Remembrance.ToolExchange(address.execution().value(), call, toToolResult(outcome)));
    // AwaitingTools#handle commits [assistantTurn, toolResults] together, exactly once, on the
    // call that completes the whole batch — never fewer than both, never just one. Testing
    // non-empty and reading getFirst() (the assistantTurn) rather than pinning the exact size
    // keeps this call site untangled from that internal shape; Context's own validating
    // constructor is what actually enforces the pairing invariant on the wire.
    List<Message> commit = transition.commit();
    if (!commit.isEmpty()) {
      memory.remember(
          new Remembrance.AssistantMessage(awaiting.responseId().value(), commit.getFirst()));
    }
  }

  private static ToolResult toToolResult(ToolOutcome outcome) {
    return switch (outcome) {
      case ToolOutcome.Returned(ToolResult result) -> result;
      case ToolOutcome.Failed(ToolError error) -> ToolResult.error(error.message());
    };
  }
}
