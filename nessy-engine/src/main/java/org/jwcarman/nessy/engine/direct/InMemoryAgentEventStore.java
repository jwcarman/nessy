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
package org.jwcarman.nessy.engine.direct;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.inference.Seq;

/**
 * A stream that is a list, for work that outlives nothing.
 *
 * <p>Enough for a turn nobody will resume, and enough to prove the seam: the same harness runs
 * against this and against a durable one without knowing which it has.
 *
 * <p>There is no {@code written_at} column here, so each append stamps its own row with {@link
 * Clock#instant()} -- the JDBC store's database clock and this one's harness clock are the same
 * kind of thing, read at the same moment: when the fact was filed.
 */
public final class InMemoryAgentEventStore implements AgentEventStore {

  private final Map<AgentId, List<AgentEvent>> streams = new ConcurrentHashMap<>();
  private final Map<AgentId, Map<Seq, Instant>> writtenAt = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryAgentEventStore() {
    this(Clock.systemUTC());
  }

  public InMemoryAgentEventStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public synchronized void append(AgentId agent, List<AgentEvent> events, Seq expectedLast) {
    List<AgentEvent> stream = streams.computeIfAbsent(agent, _ -> new ArrayList<>());
    Seq last = stream.isEmpty() ? Seq.NONE : stream.getLast().seq();
    if (!last.equals(expectedLast)) {
      throw new Conflict("expected " + expectedLast + " but the stream is at " + last);
    }
    stream.addAll(events);
    Map<Seq, Instant> stamps = writtenAt.computeIfAbsent(agent, _ -> new ConcurrentHashMap<>());
    Instant now = clock.instant();
    for (AgentEvent event : events) {
      stamps.put(event.seq(), now);
    }
  }

  @Override
  public synchronized List<AgentEvent> readFrom(AgentId agent, Seq watermark) {
    return streams.getOrDefault(agent, List.of()).stream()
        .filter(event -> event.seq().compareTo(watermark) > 0)
        .toList();
  }

  @Override
  public List<AgentEvent> sinceLastTurnStarted(AgentId agent) {
    List<AgentEvent> stream = streams.getOrDefault(agent, List.of());
    for (int i = stream.size() - 1; i >= 0; i--) {
      if (stream.get(i) instanceof AgentEvent.TurnStarted) {
        return List.copyOf(stream.subList(i, stream.size()));
      }
    }
    return List.of();
  }

  @Override
  public Instant writtenAt(AgentId agent, Seq seq) {
    Instant stamp = writtenAt.getOrDefault(agent, Map.of()).get(seq);
    if (stamp == null) {
      throw new IllegalArgumentException("no event at " + seq + " for agent " + agent.value());
    }
    return stamp;
  }
}
