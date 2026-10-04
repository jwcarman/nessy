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
package org.jwcarman.nessy.engine.narration;

import org.jwcarman.nessy.api.FailureKind;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.inference.Failure;

/**
 * What a stored event is told as. The one mapping from the agent's record to its narration: both
 * doors use it as events commit, so what a listener hears cannot differ by door. One stored event
 * is told as exactly one {@link Narration.Story} event.
 */
public final class StoryEvents {

  private StoryEvents() {}

  /** What {@code event} is told as. */
  public static Narration.Story of(AgentEvent event) {
    return switch (event) {
      // A call being tried again is the engine keeping its own promise rather than anything the
      // turn did, but it cost something, and the story is where a watcher learns that.
      case AgentEvent.InferenceAttempted attempted ->
          new Narration.InferenceRetried(
              attempted.turn(),
              kindOf(attempted.failure()),
              attempted.failure().reason(),
              attempted.usage());
      case AgentEvent.ActionsRequested asked ->
          new Narration.ActionsRequested(
              asked.turn(),
              asked.actions().stream()
                  .filter(ActionRequest.ToolCall.class::isInstance)
                  .map(ActionRequest.ToolCall.class::cast)
                  .map(
                      call ->
                          new Narration.ActionsRequested.Call(
                              call.id(), call.idempotencyKey(), call.name(), call.action()))
                  .toList(),
              asked.usage());
      case AgentEvent.ToolApproved approved -> new Narration.CallApproved(approved.callId());
      case AgentEvent.ToolDenied denied ->
          new Narration.CallDenied(denied.callId(), denied.reason());
      case AgentEvent.ToolSucceeded done -> new Narration.CallFinished(done.callId());
      case AgentEvent.ToolFailed failed ->
          new Narration.CallFailed(failed.callId(), failed.message());
      // Said as a fact once the fold has committed. The deltas a provider streamed are what is
      // ARRIVING; this is what was said, and a watcher that saw neither -- a page opened
      // mid-turn -- would otherwise never learn the answer.
      case AgentEvent.InferenceAnswered answered ->
          new Narration.Answered(answered.turn(), answered.usage());
      case AgentEvent.InferenceRefused refused ->
          new Narration.TurnRefused(refused.turn(), refused.category(), refused.usage());
      case AgentEvent.InferenceFailed failed ->
          new Narration.TurnFailed(
              failed.turn(), kindOf(failed.failure()), failed.failure().reason(), failed.usage());
      // Not a failed model call: a policy ended the turn, and no call was made.
      case AgentEvent.TurnFailed stopped ->
          new Narration.TurnStopped(stopped.turn(), stopped.reason());
      case AgentEvent.Terminated _ -> new Narration.Terminated();
      // Said even though the caller knows: the caller is not the only watcher. A page on the
      // narration stream while the request blocks, or a second one opened beside it, learns what
      // is happening only from here.
      case AgentEvent.TurnStarted started -> new Narration.TurnStarted(started.turn());
    };
  }

  private static FailureKind kindOf(Failure failure) {
    return switch (failure) {
      case Failure.Transient _ -> FailureKind.TRANSIENT;
      case Failure.Unknown _ -> FailureKind.UNKNOWN;
      case Failure.Permanent _ -> FailureKind.PERMANENT;
      case Failure.Rejected _ -> FailureKind.REJECTED;
    };
  }
}
