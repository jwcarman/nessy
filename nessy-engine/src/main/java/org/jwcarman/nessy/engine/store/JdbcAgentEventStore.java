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

package org.jwcarman.nessy.engine.store;

import java.util.List;
import java.util.Objects;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.inference.Seq;
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
 * state mint the same seq, so the second violates {@code (agent_id, seq)} and is told. That is what
 * {@code expectedLast} means here: not a version column to compare, but a claim that the seqs about
 * to be written are free -- and the database is the thing that knows.
 */
public final class JdbcAgentEventStore implements AgentEventStore {

  private static final String APPEND =
      "INSERT INTO nessy_agent_event (agent_id, seq, payload) VALUES (?, ?, ?)";

  private static final String READ_FROM =
      "SELECT payload FROM nessy_agent_event WHERE agent_id = ? AND seq > ? ORDER BY seq";

  private static final String WATERMARK =
      "SELECT seq FROM nessy_agent_watermark WHERE agent_id = ?";

  private static final String MOVE =
      """
      INSERT INTO nessy_agent_watermark (agent_id, seq)
      VALUES (?, ?)
          ON CONFLICT (agent_id) DO UPDATE SET seq = EXCLUDED.seq
      """;

  private final JdbcClient jdbc;
  private final Codec<AgentEvent> codec;

  public JdbcAgentEventStore(JdbcClient jdbc, CodecFactory codecs) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.codec = Objects.requireNonNull(codecs, "codecs must not be null").create(AgentEvent.class);
  }

  @Override
  public void append(AgentId agent, List<AgentEvent> events, Seq expectedLast) {
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(events, "events must not be null");
    for (AgentEvent event : events) {
      try {
        jdbc.sql(APPEND).params(agent.value(), event.seq().value(), codec.encode(event)).update();
      } catch (DuplicateKeyException taken) {
        // Somebody else wrote this seq, which means they decided from the state this caller
        // decided from. Its recourse is to read the agent back and decide again.
        throw new Conflict(
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

  @Override
  public List<AgentEvent> readFrom(AgentId agent, Seq watermark) {
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(watermark, "watermark must not be null");
    return jdbc.sql(READ_FROM)
        .params(agent.value(), watermark.value())
        .query((rs, _) -> codec.decode(rs.getBytes("payload")))
        .list();
  }

  @Override
  public Seq watermark(AgentId agent) {
    Objects.requireNonNull(agent, "agent must not be null");
    return jdbc.sql(WATERMARK)
        .params(agent.value())
        .query((rs, _) -> new Seq(rs.getLong("seq")))
        .optional()
        // An agent that has never closed a turn starts at the beginning, which for a new one is
        // nothing at all.
        .orElse(Seq.NONE);
  }

  @Override
  public void watermark(AgentId agent, Seq at) {
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(at, "at must not be null");
    jdbc.sql(MOVE).params(agent.value(), at.value()).update();
  }
}
