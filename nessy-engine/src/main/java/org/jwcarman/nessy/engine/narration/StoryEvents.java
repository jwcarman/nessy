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

import java.util.List;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * What a stored event is told as. The one mapping from the agent's record to its narration: both
 * doors use it as events commit, so what a listener hears cannot differ by door.
 */
public final class StoryEvents {

  private StoryEvents() {}

  /** What {@code event} is told as, in order; empty when it is not told. */
  public static List<Narration> of(AgentEvent event) {
    return switch (event) {
      // Not narrated. A watcher is told what is happening to a turn, and a call being tried
      // again is the engine keeping its own promise rather than anything the turn did. It is in
      // the story for whoever is counting what the turn spent.
      case AgentEvent.InferenceAttempted _ -> List.of();
      case AgentEvent.ActionsRequested asked ->
          List.of(
              new Narration.ActionsRequested(
                  asked.actions().stream()
                      .filter(ActionRequest.ToolCall.class::isInstance)
                      .map(ActionRequest.ToolCall.class::cast)
                      .map(
                          call ->
                              new Narration.ActionsRequested.Call(
                                  call.id(), call.name(), call.action()))
                      .toList()));
      case AgentEvent.ToolApproved approved ->
          List.of(new Narration.CallApproved(approved.callId()));
      case AgentEvent.ToolDenied denied ->
          List.of(new Narration.CallDenied(denied.callId(), denied.reason()));
      case AgentEvent.ToolSucceeded done -> List.of(new Narration.CallFinished(done.callId()));
      case AgentEvent.ToolFailed failed ->
          List.of(new Narration.CallFailed(failed.callId(), failed.message()));
      // Said as a fact once the fold has committed. The deltas a provider streamed are what is
      // ARRIVING; this is what was said, and a watcher that saw neither -- a page opened
      // mid-turn -- would otherwise never learn the answer.
      case AgentEvent.InferenceAnswered answered ->
          List.of(new Narration.Answered(), new Narration.TurnEnded(answered.turn()));
      // However it ended, it ended: the one event to hear when the story grew by a turn. An
      // answer, a refusal and a fault all close one; asking for actions does not.
      case AgentEvent.InferenceRefused refused ->
          List.of(
              new Narration.TurnRefused(refused.category()),
              new Narration.TurnEnded(refused.turn()));
      case AgentEvent.InferenceFailed failed ->
          List.of(
              new Narration.TurnFailed(failed.failure().reason()),
              new Narration.TurnEnded(failed.turn()));
      // Heard exactly as any other failed turn is. A watcher does not care whether the model
      // could not answer or a policy decided it had answered enough; either way the turn is over
      // and the reason is the whole of what is worth saying about it.
      case AgentEvent.TurnFailed ended ->
          List.of(new Narration.TurnFailed(ended.reason()), new Narration.TurnEnded(ended.turn()));
      case AgentEvent.Terminated _ -> List.of(new Narration.Terminated());
      // Said even though the caller knows: the caller is not the only watcher. A page on the
      // narration stream while the request blocks, or a second one opened beside it, learns what
      // is happening only from here.
      case AgentEvent.TurnStarted started -> List.of(new Narration.TurnStarted(started.turn()));
    };
  }
}
