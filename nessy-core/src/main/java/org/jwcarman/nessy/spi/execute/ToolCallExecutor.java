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
package org.jwcarman.nessy.spi.execute;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * Performs one {@code ExecuteTool} or {@code RequestApproval} effect: the gate travels with the
 * act. There is no door into a tool's execution that is not this executor — a call either clears
 * the gate here, in one step, or {@link #execute} yields {@link Awaited.Parked} and the gate's
 * verdict arrives later through {@link #resume}.
 *
 * <p>Yields exactly one fact — {@code ToolFinished} — whichever branch it takes: policy allow,
 * policy deny, approver allow, approver deny, an unknown tool, or a tool that threw.
 */
public interface ToolCallExecutor {

  /**
   * Gates {@code call} against its grant's policy, consulting the approver only when the policy
   * defers, then invokes it if the gate clears. Returns {@link Awaited.Parked} whenever the wait
   * must outlive this call — either the approver parks, or the tool itself parks once invoked —
   * with no {@code ToolCallCompleted} narrated for that outcome; a park is the executor contract,
   * not an error, and the loop decides tolerance for it.
   */
  Awaited<ConversationEvent> execute(ToolCall call, ConversationState state, TurnObserver observer);

  /**
   * Finishes a call whose gate verdict or whose slow completion arrived after a park: {@link
   * ToolResolution.Decided} resumes the gate (invoking on allow, denying on deny); {@link
   * ToolResolution.Completed} finishes the call directly with the result that arrived.
   */
  Awaited<ConversationEvent> resume(
      ToolCall call, ToolResolution resolution, ConversationState state, TurnObserver observer);
}
