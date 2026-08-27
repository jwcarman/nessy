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
   * Hand the approval off. <b>The order is part of the contract</b> (spec §9a, ordering ruled
   * 2026-08-26): create the computation, clip {@code term}, <b>deliver {@code ApprovalDeferred}
   * through {@code sink} and let that fold commit</b>, and only THEN run {@code callback} with the
   * id and the agreed deadline. An implementation that calls before it folds is wrong even where
   * every test of its own passes: the callback tells the world where to answer, and an answer
   * arriving before the park has committed meets a call that records no id and is dropped forever.
   *
   * <p>{@code sink} rethrows if the fold could not commit; let that propagate rather than running
   * the callback anyway. A callback that throws delivers a failure instead, riding the id the phase
   * now names.
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

  /**
   * The tool side's {@link #deferApproval}, under the same ordering contract: deliver {@code
   * ToolCallDeferred} and let it commit, then call. Or a failure.
   */
  void deferToolCall(
      ToolCall call,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink);
}
