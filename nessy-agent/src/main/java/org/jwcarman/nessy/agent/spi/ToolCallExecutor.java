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
import org.jwcarman.nessy.agent.ToolCallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Tool execution: async by contract, and two doors, neither with a conditional inside
 * (approval-lifecycle spec §4). The sink is handed per dispatch, lives one dispatch, and is never
 * invoked on the dispatching stack (§4). {@code responseId} is the committed {@code
 * ModelResponseId} that produced {@code call} (durable-deliveries spec §2), read from the fold's
 * {@code AwaitingTools} state at the dispatch site — it is how {@link ToolCallAddress}'s derivation
 * and a real {@code ToolInvocationId} become possible.
 */
public interface ToolCallExecutor {

  /**
   * Ask: evaluate the grant's approver; deliver {@code ApprovalAnswered} or (via {@code defer()})
   * {@code ApprovalDeferred}. Never runs a tool.
   */
  void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink);

  /**
   * Run: past the gate; deliver {@code ToolFinished} or {@code ToolDeferred}. Never consults an
   * approver — the answer is already a fact in the phase.
   */
  void runTool(ToolCall call, ModelResponseId responseId, Sink sink);
}
