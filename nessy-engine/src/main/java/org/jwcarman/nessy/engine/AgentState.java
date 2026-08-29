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
package org.jwcarman.nessy.engine;

import java.util.Objects;
import org.jwcarman.nessy.api.agent.Backlog;

/**
 * Everything an agent persists: identifiers, status, and human decisions. NEVER content — tool
 * results live in Memory and tool arguments live in Claims. This record serializes without {@code
 * NON_NULL}, so even idle it is not the {@code {"state":"idle"}} of the old {@code TurnState} it
 * replaced — it is {@code {"turnId":null,"phase":{"phase":"idle"},"takenEntryId":null}}. The
 * branch's own soak, driven against real Postgres, measured 356 bytes at revision 15 with three
 * claims held mid-round, and flat thereafter regardless of how long the conversation grows. That
 * flatness — not any particular byte count — is the property this record's shape is for: content
 * lives in Memory and Claims, so nothing here grows with the transcript.
 *
 * @param turnId names the turn in flight, and owns that turn's claims. Null when idle.
 * @param takenEntryId names the {@link Backlog} entry this turn consumed, if any. It is an
 *     IDENTIFIER — the same rule that admits {@code turnId} — and it exists to make {@code
 *     remember(userMessage) -&gt; backlogs().taken() -&gt; persist} recoverable: a crash between
 *     the first two steps leaves the transcript entry written but the backlog entry not yet
 *     removed, and nothing durable would say so without this field. {@link AgentActor#resume}
 *     finishes the removal on recovery. Null when no turn is in flight.
 */
public record AgentState(String turnId, Phase phase, String takenEntryId) {

  public AgentState {
    Objects.requireNonNull(phase, "phase must not be null");
  }

  public static AgentState idle() {
    return new AgentState(null, new Phase.Idle(), null);
  }

  public AgentState withPhase(Phase next) {
    return new AgentState(turnId, next, takenEntryId);
  }

  /** Names the turn about to run. Its claims are deleted under this id when the turn ends. */
  public AgentState startingTurn(String newTurnId) {
    return new AgentState(newTurnId, phase, takenEntryId);
  }

  /** Names the backlog entry this turn is about to consume — see {@link #takenEntryId}. */
  public AgentState taking(String entryId) {
    return new AgentState(turnId, phase, entryId);
  }

  /** Back to rest: no turn, no claims owed, no backlog entry outstanding. */
  public AgentState finishedTurn() {
    return new AgentState(null, new Phase.Idle(), null);
  }
}
