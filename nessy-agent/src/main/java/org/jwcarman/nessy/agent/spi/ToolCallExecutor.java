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

import java.time.Duration;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.ToolCallAddress;
import org.jwcarman.nessy.api.tool.ComputationCallback;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Tool execution: async by contract, and four doors, none with a conditional inside
 * (approval-lifecycle spec §4). The sink is handed per dispatch, lives one dispatch, and is never
 * invoked on the dispatching stack (§4). {@code responseId} is the committed {@code
 * ModelResponseId} that produced {@code call} (durable-deliveries spec §2), read from the fold's
 * {@code AwaitingTools} state at the dispatch site — it is how {@link ToolCallAddress}'s derivation
 * and a real {@code ToolInvocationId} become possible.
 *
 * <p>Two doors ask and run; two hand off. The split is the deferral-by-callback reform (spec §9a):
 * an ask or a run may RETURN a deferral, but neither creates the computation, because a party that
 * could see an id before the fold is a party that can tell the world about a wait the scope has not
 * recorded. Creating it is a separate instruction, dispatched only after the fold that recorded the
 * deferral has committed.
 */
public interface ToolCallExecutor {

  /**
   * Ask: evaluate the grant's approver; deliver {@code ApprovalAnswered} or {@code
   * ApprovalDeferralRequested}. Never runs a tool, and never touches Continuum.
   */
  void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink);

  /**
   * Hand the approval off: create the computation, clip {@code term}, run {@code callback} with the
   * id and the agreed deadline, and deliver {@code ApprovalDeferred}. A callback that throws
   * delivers a failure instead (spec §9a).
   *
   * @param request the frozen question, which becomes the computation's continuation
   * @param callback what tells the world where to answer
   * @param term how long the approver asked for, before clipping
   */
  void deferApproval(
      ToolCall call,
      ApprovalRequest request,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink);

  /**
   * Run: past the gate; deliver {@code ToolFinished} or {@code ToolCallDeferralRequested}. It never
   * consults an approver — the answer is already a fact in the phase — and it creates no
   * computation.
   */
  void runTool(ToolCall call, ModelResponseId responseId, Sink sink);

  /** The tool side's {@link #deferApproval}: delivers {@code ToolCallDeferred}, or a failure. */
  void deferToolCall(
      ToolCall call,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink);
}
