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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A deadline that outlives the actor which set it.
 *
 * <p>An in-memory timer dies with its actor. That is why an approval parked on a person for three
 * days used to require the agent to stay resident for three days — and why an agent that was
 * passivated anyway lost the deadline entirely, silently. A row does not die, and reading it back
 * RESUMES the remaining time rather than restarting a term someone was promised.
 *
 * <p><b>Engine-internal.</b> Nothing outside the engine sets or reads a reminder, so this is not an
 * extension point: the engine needs it, so the engine provides it.
 *
 * <p><b>The payload is an ADDRESS, never a continuation.</b> What goes in it is the coordinates a
 * reply token already carries — which agent, which call — so a fired reminder becomes a message
 * sent to a logical address. Storing behaviour here is the mistake this project already made once
 * and removed.
 */
final class Reminders {

  private static final String COLUMNS = "agent_type, agent_id, call_id, expires_at";
  private static final String WHERE_CALL = " WHERE agent_type = ? AND agent_id = ? AND call_id = ?";
  private static final String DELETE = "DELETE FROM nessy_reminder" + WHERE_CALL;
  private static final String INSERT =
      "INSERT INTO nessy_reminder (agent_type, agent_id, call_id, expires_at) VALUES (?, ?, ?, ?)";
  private static final String DUE =
      "SELECT "
          + COLUMNS
          + " FROM nessy_reminder WHERE expires_at <= ? ORDER BY expires_at LIMIT ?";
  private static final String READ = "SELECT " + COLUMNS + " FROM nessy_reminder" + WHERE_CALL;

  /**
   * A reminder as the store holds it.
   *
   * <p>Written out rather than generated because a record compares an array by IDENTITY: two
   * reminders read from the same row would not be equal, and a payload would print as a type name
   * and a hash rather than as anything a reader could use.
   */
  /** A deadline, and whose it is. */
  record Reminder(String agentType, String agentId, String callId, Instant expiresAt) {}

  private final JdbcClient jdbc;

  Reminders(DataSource dataSource) {
    this.jdbc =
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
  }

  /**
   * Remembers to hand {@code payload} back once {@code expiresAt} has passed.
   *
   * <p>An upsert on the key: reminding an existing key MOVES it, which is how a term is extended
   * and how the sweep backs off a reminder whose owner has not settled it. Delete-then-insert
   * rather than vendor upsert syntax, so one statement set serves every database.
   */
  void remind(String agentType, String agentId, String callId, Instant expiresAt) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    jdbc.sql(DELETE).params(agentType, agentId, callId).update();
    jdbc.sql(INSERT).params(agentType, agentId, callId, Timestamp.from(expiresAt)).update();
  }

  /**
   * Reminders already due, EARLIEST FIRST, at most {@code limit}.
   *
   * <p>{@code now} is a parameter rather than SQL {@code now()} because time comes from an injected
   * clock everywhere else in this engine, and a sweep whose notion of time is the database's cannot
   * be tested.
   */
  List<Reminder> due(Instant now, int limit) {
    Objects.requireNonNull(now, "now must not be null");
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1, was " + limit);
    }
    return jdbc.sql(DUE)
        .params(Timestamp.from(now), limit)
        .query(
            (row, number) ->
                new Reminder(
                    row.getString("agent_type"),
                    row.getString("agent_id"),
                    row.getString("call_id"),
                    row.getTimestamp("expires_at").toInstant()))
        .list();
  }

  /** What this key is waiting for, if anything — the remaining term a restarted actor re-arms. */
  Optional<Reminder> find(String agentType, String agentId, String callId) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    return jdbc.sql(READ)
        .params(agentType, agentId, callId)
        .query(
            (row, number) ->
                new Reminder(
                    row.getString("agent_type"),
                    row.getString("agent_id"),
                    row.getString("call_id"),
                    row.getTimestamp("expires_at").toInstant()))
        .optional();
  }

  /** Forgets it. Silent when there is nothing to forget: settling twice is not an error. */
  void cancel(String agentType, String agentId, String callId) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    jdbc.sql(DELETE).params(agentType, agentId, callId).update();
  }
}
