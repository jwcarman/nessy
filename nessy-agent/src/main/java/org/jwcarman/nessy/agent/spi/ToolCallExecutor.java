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
package org.jwcarman.nessy.agent.spi;

import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.ToolInvocationId;

/**
 * Tool execution: async by contract. The sink is handed per dispatch, lives one dispatch, and is
 * never invoked on the dispatching stack (§4). {@code responseId} is the committed {@code
 * ModelResponseId} that produced {@code call} (durable-deliveries spec §2), read from the fold's
 * {@code AwaitingTools} state at the dispatch site — it is how {@link CallAddress}'s derivation and
 * a real {@code ToolInvocationId} become possible at the gate.
 */
public interface ToolCallExecutor {

  void executeTool(ToolCall call, ModelResponseId responseId, Sink sink);

  /**
   * The post-gate door (durable-deliveries spec §5a, §6): dispatches {@code call} straight to the
   * tool with an already-known {@code address}/{@code invocation}, skipping the policy/approval
   * gate entirely. Used only where the gate has already run for this exact invocation — a granted
   * approval's tool call, or the reaper's redispatch of a {@code RETRYABLE} overdue computation —
   * never for a fresh dispatch. The default falls back to a fresh, gate-checked dispatch (correct
   * for any {@link ToolCallExecutor} that has no gate to skip, e.g. test doubles); {@link
   * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor} is the one implementation that actually
   * bypasses its gate here.
   */
  default void executeGrantedTool(
      ToolCall call, CallAddress address, ToolInvocationId invocation, Sink sink) {
    executeTool(call, ModelResponseId.of(invocation.responseId()), sink);
  }

  /**
   * The synchronous post-gate door (durable-deliveries spec §5a invariant 5): runs {@code call}
   * straight to the tool, skipping the policy/approval gate exactly like {@link
   * #executeGrantedTool}, but on the CALLING thread and returning the {@link ToolExecution}
   * directly instead of delivering asynchronously. {@link
   * org.jwcarman.nessy.agent.durable.DeliveryWorker}'s grant arm needs the outcome in hand before
   * it decides which atomic batch to commit — an immediate outcome rides the result's own
   * fold-advance batch, a deferred one means the durable computation is already the owner — so this
   * is the one door that may not hand off to an executor. The default throws: only {@link
   * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor} implements this meaningfully.
   */
  default ToolExecution executeGrantedToolNow(
      ToolCall call, CallAddress address, ToolInvocationId invocation) {
    throw new UnsupportedOperationException(
        "this ToolCallExecutor does not support synchronous granted execution");
  }
}
