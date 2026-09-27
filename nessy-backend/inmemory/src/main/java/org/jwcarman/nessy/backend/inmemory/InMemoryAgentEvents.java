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
package org.jwcarman.nessy.backend.inmemory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;

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
public final class InMemoryAgentEvents implements AgentEvents {

  /**
   * One event as it is kept: encoded, with the two facts a reader selects on left outside the
   * bytes.
   *
   * <p>The durable table does exactly this -- a payload blob beside a {@code seq} column and a
   * {@code starts_turn} flag it indexes -- because answering "everything after this point" or
   * "since the last turn opened" by decoding every event to look at it would be absurd there. It is
   * only wasteful here, but keeping the same shape means the two stores answer the same questions
   * the same way rather than by coincidence.
   */
  private record Stored(Seq seq, boolean startsTurn, byte[] bytes) {}

  /** An agent is a type and an id together, exactly as the durable table's key is. */
  private record Key(AgentType type, AgentId agent) {}

  private final Map<Key, List<Stored>> streams = new ConcurrentHashMap<>();
  private final Map<Key, Map<Seq, Instant>> writtenAt = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Codec<AgentEvent> codec;

  public InMemoryAgentEvents(CodecFactory codecs) {
    this(codecs, Clock.systemUTC());
  }

  public InMemoryAgentEvents(CodecFactory codecs, Clock clock) {
    this.codec = Objects.requireNonNull(codecs, "codecs must not be null").create(AgentEvent.class);
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public synchronized void append(
      AgentType type, AgentId agent, List<AgentEvent> events, Seq expectedLast) {
    Key key = key(type, agent);
    List<Stored> stream = streams.computeIfAbsent(key, _ -> new ArrayList<>());
    Seq last = stream.isEmpty() ? Seq.NONE : stream.getLast().seq();
    if (!last.equals(expectedLast)) {
      throw new Conflict("expected " + expectedLast + " but the stream is at " + last);
    }
    for (AgentEvent event : events) {
      stream.add(
          new Stored(event.seq(), event instanceof AgentEvent.TurnStarted, codec.encode(event)));
    }
    Map<Seq, Instant> stamps = writtenAt.computeIfAbsent(key, _ -> new ConcurrentHashMap<>());
    Instant now = clock.instant();
    for (AgentEvent event : events) {
      stamps.put(event.seq(), now);
    }
  }

  /**
   * <b>A snapshot, taken under the lock, then decoded lazily outside it.</b> Copying the references
   * is what makes this safe to hand out: an append that lands while a caller is still reading
   * cannot change what is being read, and nothing holds the lock for as long as the stream is open.
   * Decoding stays lazy, so a caller that stops early decodes only what it looked at.
   *
   * <p>Nothing needs closing here -- there is no cursor and no connection -- but callers close it
   * anyway, because they cannot know which store they have.
   */
  @Override
  public Stream<AgentEvent> streamFrom(AgentType type, AgentId agent, Seq watermark) {
    Objects.requireNonNull(watermark, "watermark must not be null");
    List<Stored> snapshot;
    synchronized (this) {
      snapshot = List.copyOf(streams.getOrDefault(key(type, agent), List.of()));
    }
    return snapshot.stream()
        .filter(stored -> stored.seq().compareTo(watermark) > 0)
        .map(stored -> codec.decode(stored.bytes()));
  }

  @Override
  public List<AgentEvent> sinceLastTurnStarted(AgentType type, AgentId agent) {
    List<Stored> stream = streams.getOrDefault(key(type, agent), List.of());
    for (int i = stream.size() - 1; i >= 0; i--) {
      if (stream.get(i).startsTurn()) {
        return stream.subList(i, stream.size()).stream()
            .map(stored -> codec.decode(stored.bytes()))
            .toList();
      }
    }
    return List.of();
  }

  @Override
  public Instant writtenAt(AgentType type, AgentId agent, Seq seq) {
    Instant stamp = writtenAt.getOrDefault(key(type, agent), Map.of()).get(seq);
    if (stamp == null) {
      throw new IllegalArgumentException("no event at " + seq + " for agent " + agent.value());
    }
    return stamp;
  }

  private static Key key(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return new Key(type, agent);
  }
}
