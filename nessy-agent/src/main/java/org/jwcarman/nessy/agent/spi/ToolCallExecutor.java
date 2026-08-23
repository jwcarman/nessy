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

import java.util.Optional;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.substrate.Substrate;

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
   * The synchronous post-gate door (durable-deliveries spec §5a invariant 5): runs {@code call}
   * straight to the tool, skipping the policy/approval gate entirely, on the CALLING thread,
   * returning the {@link ToolExecution} directly rather than delivering asynchronously. Used only
   * where the gate has already run for this exact invocation — a granted approval's tool call, or
   * the reaper's redispatch of a {@code RETRYABLE} overdue computation — never for a fresh
   * dispatch. Callers need the outcome in hand, synchronously, before they decide what to commit:
   * {@code org.jwcarman.nessy.agent.DeliveryWorker}'s grant arm decides which atomic batch to build
   * from it, and the reaper decides whether to fold an immediate answer straight into the pipeline
   * (spec §6) rather than leave its computation orphaned.
   *
   * <p>{@code alsoCommit} is the transfer-then-dispatch door (spec §5a invariant 5, honesty
   * amendment): when present and the invocation defers, an implementation that can must commit
   * {@code alsoCommit} in the SAME atomic batch as the durable computation's own creation — before
   * control returns here, not before the tool's {@code execute()} runs (a tool reveals deferral
   * only by returning {@code Awaited.deferred()}, so {@code execute()} necessarily runs first). The
   * honest crash property: a crash between the tool's external start and the batch leaves the
   * delivery to be redriven and the external side effect to be re-run — at-least-once, per the
   * {@code Tool} contract, not "nothing to duplicate." What the batch DOES guarantee is the
   * single-winner property within one host: two concurrent claimants racing the same batch leave
   * exactly one committed (the worker's own claim, not this batch, is what makes that true — see
   * {@code org.jwcarman.nessy.agent.DeliveryWorker}'s javadoc). The default throws: only {@link
   * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor} implements this meaningfully, and
   * nothing calls it on a {@link ToolCallExecutor} without a gate to skip.
   */
  default ToolExecution executeGrantedToolNow(
      ToolCall call,
      CallAddress address,
      ToolInvocationId invocation,
      Optional<Substrate.Op> alsoCommit) {
    throw new UnsupportedOperationException(
        "this ToolCallExecutor does not support synchronous granted execution");
  }
}
