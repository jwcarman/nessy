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
package org.jwcarman.nessy.memory.notebook;

import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A notebook in a table of its own.
 *
 * <p><b>The index costs no bodies.</b> {@link #headings(AgentId)} selects two columns, so a note's
 * body cannot reach the model through the index even by accident — the promise this design rests on
 * is enforced by the query rather than by remembering to project after loading. That is the whole
 * argument for a table shaped for its own reads: the old store returned whole entries and trimmed
 * them in Java, which worked exactly as long as nobody edited it carelessly.
 *
 * <p><b>A note is user data.</b> Nothing rebuilds one, so nothing here expires, and a notebook is
 * not scratch space however much it looks like the claim store next door.
 */
public final class JdbcNotebook implements Notebook {

  /** No vowels, so an id cannot spell anything; no look-alikes, so a model cannot mistype one. */
  private static final String ALPHABET = "bcdfghjkmnpqrstvwxz23456789";

  private static final int ID_LENGTH = 10;

  private static final int MINT_ATTEMPTS = 100;

  private static final SecureRandom RANDOM = new SecureRandom();

  private static final String SELECT_HEADINGS =
      "SELECT note_id, hook FROM nessy_note WHERE agent_type = ? AND agent_id = ? ORDER BY ordinal";
  private static final String SELECT_ONE =
      "SELECT note_id, hook, body FROM nessy_note "
          + "WHERE agent_type = ? AND agent_id = ? AND note_id = ?";
  private static final String INSERT =
      "INSERT INTO nessy_note (agent_type, agent_id, note_id, hook, body, ordinal) "
          + "VALUES (?, ?, ?, ?, ?, ?)";
  private static final String UPDATE =
      "UPDATE nessy_note SET hook = ?, body = ? "
          + "WHERE agent_type = ? AND agent_id = ? AND note_id = ?";
  private static final String DELETE =
      "DELETE FROM nessy_note WHERE agent_type = ? AND agent_id = ? AND note_id = ?";
  private static final String NEXT_ORDINAL =
      "SELECT coalesce(max(ordinal), 0) + 1 FROM nessy_note WHERE agent_type = ? AND agent_id = ?";
  private static final String EXISTS =
      "SELECT count(*) FROM nessy_note WHERE agent_type = ? AND agent_id = ? AND note_id = ?";

  private final JdbcClient jdbc;
  private final String agentType;

  public JdbcNotebook(DataSource dataSource, AgentType agentType) {
    this.jdbc =
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null").name();
  }

  @Override
  public List<Heading> headings(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    return jdbc.sql(SELECT_HEADINGS)
        .params(agentType, agentId.value())
        .query((row, number) -> new Heading(row.getString("note_id"), row.getString("hook")))
        .list();
  }

  @Override
  public Optional<Entry> find(AgentId agentId, String id) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(id, "id must not be null");
    return jdbc.sql(SELECT_ONE)
        .params(agentType, agentId.value(), id)
        .query(
            (row, number) ->
                new Entry(row.getString("note_id"), row.getString("hook"), row.getString("body")))
        .optional();
  }

  @Override
  public Entry write(AgentId agentId, String hook, String body) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    // Constructed first, so a blank hook or body is refused before anything is written.
    Entry entry = new Entry(mintUnusedIn(agentId), hook, body);
    jdbc.sql(INSERT)
        .params(agentType, agentId.value(), entry.id(), hook, body, nextOrdinal(agentId))
        .update();
    return entry;
  }

  @Override
  public Optional<Entry> revise(AgentId agentId, String id, String hook, String body) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(id, "id must not be null");
    Entry revised = new Entry(id, hook, body);
    // No ordinal touched: a revision replaces what a note says, never where it sits in the index.
    int changed = jdbc.sql(UPDATE).params(hook, body, agentType, agentId.value(), id).update();
    return changed == 0 ? Optional.empty() : Optional.of(revised);
  }

  @Override
  public void forget(AgentId agentId, String id) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(id, "id must not be null");
    jdbc.sql(DELETE).params(agentType, agentId.value(), id).update();
  }

  private long nextOrdinal(AgentId agentId) {
    return jdbc.sql(NEXT_ORDINAL).params(agentType, agentId.value()).query(Long.class).single();
  }

  /**
   * An id no note in this notebook is using.
   *
   * <p>Ten characters from an unambiguous alphabet: short enough that a line of index costs almost
   * nothing and a model can copy one back without slipping, and random enough that two notes filed
   * in the same breath do not collide. Uniqueness is CHECKED rather than assumed, because "unique
   * within one notebook" is a promise this class makes and a probability is not a promise.
   */
  private String mintUnusedIn(AgentId agentId) {
    for (int attempt = 0; attempt < MINT_ATTEMPTS; attempt++) {
      String id = mint();
      if (!exists(agentId, id)) {
        return id;
      }
    }
    throw new IllegalStateException(
        "could not mint an unused notebook id in " + MINT_ATTEMPTS + " attempts");
  }

  private boolean exists(AgentId agentId, String id) {
    return jdbc.sql(EXISTS).params(agentType, agentId.value(), id).query(Long.class).single() > 0;
  }

  private static String mint() {
    StringBuilder id = new StringBuilder(ID_LENGTH);
    for (int i = 0; i < ID_LENGTH; i++) {
      id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return id.toString();
  }
}
