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

package org.jwcarman.nessy.engine.history;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.TurnId;
import org.jwcarman.nessy.inference.turn.Turn;

/**
 * One agent's event stream, read as the turns it amounts to.
 *
 * <p>What lets the direct door use the same {@link org.jwcarman.nessy.engine.inference
 * .ContextAssembler} the queued one does, and with it the same ambient sources, the same tail
 * window and the same summaries. Assembling a context is one job; which door asked is not part of
 * it.
 *
 * <p><b>The cap is applied in memory, which the interface warns about.</b> A store that can count
 * and slice in a query should; this one holds an append-only stream and has to fold it to know
 * where the turn boundaries are, so the read is the whole stream either way. That is a property of
 * this store rather than a licence for the next one.
 */
public final class EventStreamHistory implements TurnHistory {

  private final AgentEventStore events;
  private final Transcript transcript;
  private final AgentId agent;

  public EventStreamHistory(AgentEventStore events, Transcript transcript, AgentId agent) {
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
  }

  private List<Turn> all() {
    List<AgentEvent> stream = events.readFrom(agent, Seq.NONE);
    return transcript.of(stream);
  }

  private static List<Turn> lastOf(List<Turn> turns, int keep) {
    if (keep <= 0 || turns.isEmpty()) {
      return List.of();
    }
    return turns.subList(Math.max(0, turns.size() - keep), turns.size());
  }

  @Override
  public List<Turn> lastTurns(int turns) {
    return List.copyOf(lastOf(all(), turns));
  }

  @Override
  public List<Turn> turnsFrom(long fromTurn) {
    return all().stream().filter(turn -> turn.id().value() >= fromTurn).toList();
  }

  @Override
  public List<Turn> lastTurnsAfter(TurnId through, int turns) {
    List<Turn> after = all().stream().filter(turn -> turn.id().value() > through.value()).toList();
    return List.copyOf(lastOf(after, turns));
  }

  @Override
  public long turnsAfter(long through) {
    return all().stream().filter(turn -> turn.id().value() > through).count();
  }
}
