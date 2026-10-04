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

package org.jwcarman.nessy.backend.jdbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEventConflict;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An agent's facts in {@code nessy_agent_event}, and where to start reading them.
 *
 * <p>The durable half of the seam both doors need. Nothing here holds content: every block is a
 * reference into {@code nessy_payload}, so this table is a list of what happened rather than a
 * second copy of what was said.
 *
 * <p><b>Replay starts at the watermark, not at the beginning.</b> It sits at a turn boundary, so
 * reading an agent back costs one turn's events however long it has lived. Past a closed turn there
 * is nothing after it and the agent comes back idle; in the middle of one, what comes back is that
 * turn, and it carries on from where it stopped.
 *
 * <p><b>The primary key is the concurrency control.</b> Two writers that decided from the same
 * state mint the same seq, so the second violates {@code (agent_type, agent_id, seq)} and is told.
 * That is what {@code expectedLast} means here: not a version column to compare, but a claim that
 * the seqs about to be written are free -- and the database is the thing that knows.
 */
public final class JdbcAgentEvents implements AgentEvents {

  private static final String TYPE_REQUIRED = "type must not be null";
  private static final String AGENT_REQUIRED = "agent must not be null";
  private static final String PAYLOAD = "payload";

  private static final String APPEND =
      "INSERT INTO nessy_agent_event (agent_type, agent_id, seq, starts_turn, payload, written_at)"
          + " VALUES (?, ?, ?, ?, ?, ?)";

  private static final String READ_FROM =
      "SELECT payload FROM nessy_agent_event"
          + " WHERE agent_type = ? AND agent_id = ? AND seq > ? ORDER BY seq";

  private static final String READ_WRITTEN_FROM =
      "SELECT payload, written_at FROM nessy_agent_event"
          + " WHERE agent_type = ? AND agent_id = ? AND seq > ? ORDER BY seq LIMIT ?";

  private static final String WRITTEN_AT =
      "SELECT written_at FROM nessy_agent_event"
          + " WHERE agent_type = ? AND agent_id = ? AND seq = ?";

  /**
   * The last turn, and nothing before it.
   *
   * <p>Two index lookups: where the last turn started, then the rows at or after it. Never a scan,
   * and never the history -- the answer is one turn long whatever the agent has been through.
   */
  private static final String LAST_TURN =
      """
      SELECT payload
        FROM nessy_agent_event
       WHERE agent_type = ?
         AND agent_id = ?
         AND seq >= COALESCE((SELECT MAX(seq)
                                FROM nessy_agent_event
                               WHERE agent_type = ?
                                 AND agent_id = ?
                                 AND starts_turn), 0)
       ORDER BY seq
      """;

  private final JdbcClient jdbc;
  private final Codec<AgentEvent> codec;

  public JdbcAgentEvents(JdbcClient jdbc, CodecFactory codecs) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.codec = Objects.requireNonNull(codecs, "codecs must not be null").create(AgentEvent.class);
  }

  @Override
  public void append(
      AgentType type, AgentId agent, List<AgentEvent> events, Seq expectedLast, Instant at) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(agent, AGENT_REQUIRED);
    Objects.requireNonNull(events, "events must not be null");
    Objects.requireNonNull(at, "at must not be null");
    OffsetDateTime written = at.atOffset(ZoneOffset.UTC);
    for (AgentEvent event : events) {
      try {
        jdbc.sql(APPEND)
            .params(
                type.value(),
                agent.value(),
                event.seq().value(),
                event instanceof AgentEvent.TurnStarted,
                codec.encode(event),
                written)
            .update();
      } catch (DuplicateKeyException _) {
        // Somebody else wrote this seq, which means they decided from the state this caller
        // decided from. Its recourse is to read the agent back and decide again.
        throw new AgentEventConflict(
            "another writer reached "
                + event.seq()
                + " for agent "
                + agent.value()
                + "; expected "
                + expectedLast
                + " to be last");
      }
    }
  }

  /**
   * <b>Overridden rather than inherited, because the two are different queries and not one wrapped
   * in the other.</b> The default collects {@link #streamFrom}, which on this store means {@code
   * queryForStream} -- a ResultSet-backed spliterator whose connection is held until the stream
   * closes. {@code list()} goes through {@code RowMapperResultSetExtractor} instead: one loop into
   * one list, resources released by the template before it returns.
   *
   * <p>Worth the extra method because the reads that matter are small. One turn, one lookup, the
   * boundary replay -- all of them want every row they asked for, and none of them wants a pipeline
   * per row or a connection whose release depends on somebody remembering to close.
   */
  @Override
  public List<AgentEvent> readFrom(AgentType type, AgentId agent, Seq watermark) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(agent, AGENT_REQUIRED);
    Objects.requireNonNull(watermark, "watermark must not be null");
    return jdbc.sql(READ_FROM)
        .params(type.value(), agent.value(), watermark.value())
        .query((rs, _) -> codec.decode(rs.getBytes(PAYLOAD)))
        .list();
  }

  /**
   * <b>Whether this actually streams depends on the caller, and that is worth knowing.</b> pgjdbc
   * uses a server-side cursor only when the connection is in a transaction AND a fetch size is set.
   * The fetch size is set here; the transaction is the caller's. Inside one -- the fold's own reads
   * -- rows arrive in batches and a caller that stops early stops the reading. Outside one, the
   * driver buffers the whole result before the first element, so this is lazy in shape and eager in
   * fact.
   *
   * <p>It is still the right primitive either way: correctness does not depend on which happened,
   * and the case that matters for a long story is the one inside a transaction.
   */
  @Override
  public Stream<AgentEvent> streamFrom(AgentType type, AgentId agent, Seq watermark) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(agent, AGENT_REQUIRED);
    Objects.requireNonNull(watermark, "watermark must not be null");
    return jdbc
        .sql(READ_FROM)
        .param(type.value())
        .param(agent.value())
        .param(watermark.value())
        .query((rs, _) -> codec.decode(rs.getBytes(PAYLOAD)))
        .stream();
  }

  /** One query for the payload and the time, with the limit in the query. */
  @Override
  public List<Written> readWrittenFrom(AgentType type, AgentId agent, Seq after, int limit) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(agent, AGENT_REQUIRED);
    Objects.requireNonNull(after, "after must not be null");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return jdbc.sql(READ_WRITTEN_FROM)
        .params(type.value(), agent.value(), after.value(), limit)
        .query(
            (rs, _) ->
                new Written(
                    codec.decode(rs.getBytes(PAYLOAD)),
                    rs.getObject("written_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  @Override
  public List<AgentEvent> sinceLastTurnStarted(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(agent, AGENT_REQUIRED);
    return jdbc.sql(LAST_TURN)
        .params(type.value(), agent.value(), type.value(), agent.value())
        .query((rs, _) -> codec.decode(rs.getBytes(PAYLOAD)))
        .list();
  }

  @Override
  public Instant writtenAt(AgentType type, AgentId agent, Seq seq) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(agent, AGENT_REQUIRED);
    Objects.requireNonNull(seq, "seq must not be null");
    return jdbc.sql(WRITTEN_AT)
        .params(type.value(), agent.value(), seq.value())
        .query((rs, _) -> rs.getObject("written_at", OffsetDateTime.class).toInstant())
        .optional()
        .orElseThrow(
            () ->
                new IllegalArgumentException("no event at " + seq + " for agent " + agent.value()));
  }
}
