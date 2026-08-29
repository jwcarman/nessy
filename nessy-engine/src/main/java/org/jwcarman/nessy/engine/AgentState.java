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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.agent.BacklogItem;
import org.jwcarman.nessy.api.agent.Coalescer;

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
public record AgentState(
    String turnId, Phase phase, BacklogItem<String> inFlight, List<BacklogItem<String>> backlog) {

  public AgentState {
    Objects.requireNonNull(phase, "phase must not be null");
    backlog = backlog == null ? List.of() : List.copyOf(backlog);
  }

  public static AgentState idle() {
    return new AgentState(null, new Phase.Idle(), null, List.of());
  }

  /** The backlog after {@code coalescer} decides what {@code incoming} does to it. */
  /**
   * The backlog after {@code coalescer} decides what {@code incoming} does to it.
   *
   * <p>The coalescer sees the WAITING items only, never {@link #inFlight()}. That separation is the
   * whole reason a separate slot exists: a policy that supersedes on a key would otherwise merge
   * away the very observation a turn is running on, and the turn would finish by discarding an item
   * that is no longer the one it processed.
   */
  public AgentState ingesting(Coalescer<String> coalescer, BacklogItem<String> incoming) {
    return new AgentState(turnId, phase, inFlight, coalescer.ingest(backlog, incoming));
  }

  /**
   * The observation this turn is working on — the head, which stays until the turn ends.
   *
   * <p>There is no separate "in flight" slot, and that is a safety property rather than a
   * simplification. A slot exists to hold an item ALREADY REMOVED from the backlog but not yet
   * remembered; a crash in that window loses the observation outright. Leaving the item at the head
   * until {@link #finishedTurn()} means the window never opens: recovery re-reads the same head,
   * and {@code remember} is idempotent by the item's id.
   */
  /** The observation this turn is working on — out of the coalescer's reach until the turn ends. */
  public BacklogItem<String> current() {
    if (inFlight == null) {
      throw new IllegalStateException("no observation is being worked on");
    }
    return inFlight;
  }

  /**
   * Moves the head of the backlog into flight — ONE durable write.
   *
   * <p>Taking and recording-that-it-was-taken used to be two writes against a separate store, with
   * a crash window between them that a {@code takenEntryId} breadcrumb existed to reconcile. Here
   * they are the same write, and there is nothing left to reconcile.
   */
  public AgentState taking() {
    if (backlog.isEmpty()) {
      throw new IllegalStateException("nothing to take");
    }
    return new AgentState(
        turnId, phase, backlog.getFirst(), List.copyOf(backlog.subList(1, backlog.size())));
  }

  /** Whether anything is waiting to become a turn. */
  public boolean hasWork() {
    return !backlog.isEmpty();
  }

  public AgentState withPhase(Phase next) {
    return new AgentState(turnId, next, inFlight, backlog);
  }

  /** Names the turn about to run. Its claims are deleted under this id when the turn ends. */
  public AgentState startingTurn(String newTurnId) {
    return new AgentState(newTurnId, phase, inFlight, backlog);
  }

  /** Back to rest: no turn, no claims owed, no backlog entry outstanding. */
  public AgentState finishedTurn() {
    return new AgentState(null, new Phase.Idle(), null, backlog);
  }
}
