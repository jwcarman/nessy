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
package org.jwcarman.nessy.narration.odyssey;

import java.util.Objects;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;

/**
 * Journals every event about every agent to that agent's stream.
 *
 * <p>Narration is best-effort by the engine's contract; written to a journal it also becomes
 * <em>resumable</em>: a page that was closed asks for everything after the last event it saw and
 * gets it, which no fan-out held in memory can offer.
 *
 * <p><b>What is written.</b> The event itself, as its JSON: a {@code type} naming the kind, then
 * its fields as Jackson sees the record -- a {@code CallId} is its string, a {@code TurnId} its
 * number. The SSE event name is that same kind, so a browser can listen for {@code content-delta}
 * and a subscriber with a mapper of its own gets the event back typed.
 */
public class OdysseyNarrator implements NarrationListener {

  private final AgentStreams streams;

  public OdysseyNarrator(AgentStreams streams) {
    this.streams = Objects.requireNonNull(streams, "streams must not be null");
  }

  @Override
  public void on(Narrated narrated) {
    Narration event = narrated.event();
    streams.stream(narrated.agentType(), narrated.agentId()).publish(nameOf(event), event);
  }

  /**
   * The event name on the wire: the kind the event declares in its own JSON. Spelled out here
   * rather than read off the annotation on every event; a test holds the two together.
   */
  public static String nameOf(Narration event) {
    return switch (event) {
      case Narration.TurnStarted _ -> "turn-started";
      case Narration.Thinking _ -> "thinking";
      case Narration.Answered _ -> "answered";
      case Narration.TurnStopped _ -> "turn-stopped";
      case Narration.TurnFailed _ -> "turn-failed";
      case Narration.TurnRefused _ -> "turn-refused";
      case Narration.InferenceRetried _ -> "inference-retried";
      case Narration.Commentary _ -> "commentary";
      case Narration.ActionsRequested _ -> "actions-requested";
      case Narration.CallApproved _ -> "call-approved";
      case Narration.CallDenied _ -> "call-denied";
      case Narration.CallFinished _ -> "call-finished";
      case Narration.CallFailed _ -> "call-failed";
      case Narration.Terminated _ -> "terminated";
      case Narration.ApprovalSought _ -> "approval-sought";
      case Narration.ApprovalDeferred _ -> "approval-deferred";
      case Narration.CallDeferred _ -> "call-deferred";
      case Narration.ThinkingDelta _ -> "thinking-delta";
      case Narration.ContentDelta _ -> "content-delta";
    };
  }
}
