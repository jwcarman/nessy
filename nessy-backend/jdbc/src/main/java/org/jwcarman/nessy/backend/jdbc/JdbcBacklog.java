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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.codec.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlog;
import org.jwcarman.nessy.backend.backlog.Pull;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One agent's backlog, as rows.
 *
 * <p>Built inside the transaction that already holds the agent's row, and thrown away when it
 * commits. Nothing here locks: the caller took the lock before there was a backlog to talk about,
 * which is what lets these be plain statements and a snapshot be safe to hold.
 *
 * <p>Ordinals are minted from the highest already there. They are the backlog's own numbering and
 * say nothing about events -- an item may never become one.
 *
 * @param <I> the application's input type
 */
public final class JdbcBacklog<I> implements Backlog<I> {

  private static final String ITEM_REQUIRED = "item must not be null";

  private static final String ALL =
      """
      SELECT arrived_at, payload
        FROM nessy_agent_backlog
       WHERE agent_type = ? AND agent_id = ?
       ORDER BY ordinal
      """;

  private static final String NEXT_ORDINAL =
      """
      SELECT COALESCE(MAX(ordinal), 0) + 1
        FROM nessy_agent_backlog
       WHERE agent_type = ? AND agent_id = ?
      """;

  /**
   * One before the front. Negative is fine and expected: an ordinal is a position, not a count, and
   * an empty backlog starts over at one whatever it held before.
   */
  private static final String FIRST_ORDINAL =
      """
      SELECT COALESCE(MIN(ordinal), 2) - 1
        FROM nessy_agent_backlog
       WHERE agent_type = ? AND agent_id = ?
      """;

  private static final String INSERT =
      """
      INSERT INTO nessy_agent_backlog (agent_type, agent_id, ordinal, arrived_at, payload)
      VALUES (?, ?, ?, ?, ?)
      """;

  private static final String CLEAR =
      "DELETE FROM nessy_agent_backlog WHERE agent_type = ? AND agent_id = ?";

  private static final String TAKE =
      """
      DELETE FROM nessy_agent_backlog
       WHERE (agent_type, agent_id, ordinal) IN (SELECT agent_type, agent_id, ordinal
                                                   FROM nessy_agent_backlog
                                                  WHERE agent_type = ? AND agent_id = ?
                                                  ORDER BY ordinal
                                                  LIMIT 1)
      RETURNING arrived_at, payload
      """;

  private static final String COUNT =
      "SELECT COUNT(*) FROM nessy_agent_backlog WHERE agent_type = ? AND agent_id = ?";

  private static final String DROP_OLDEST =
      """
      DELETE FROM nessy_agent_backlog
       WHERE (agent_type, agent_id, ordinal) IN (SELECT agent_type, agent_id, ordinal
                                                   FROM nessy_agent_backlog
                                                  WHERE agent_type = ? AND agent_id = ?
                                                  ORDER BY ordinal
                                                  LIMIT ?)
      """;

  private final JdbcClient jdbc;
  private final Codec<I> codec;
  private final Agents agents;
  private final AgentType agentType;
  private final AgentId agent;

  public JdbcBacklog(
      JdbcClient jdbc, Codec<I> codec, Agents agents, AgentType agentType, AgentId agent) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
    this.agents = Objects.requireNonNull(agents, "agents must not be null");
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
  }

  /**
   * Not on {@link org.jwcarman.nessy.api.Coalescing}: a coalescing policy has no business taking.
   */
  @Override
  public Pull<I> take() {
    // Removed and returned in one statement, so nothing can see it waiting after it has been
    // taken. The caller is holding the agent's row, so nothing else is looking anyway.
    Optional<BacklogItem<I>> next =
        jdbc.sql(TAKE)
            .params(agentType.value(), agent.value())
            .query((rs, _) -> item(rs))
            .optional();
    if (next.isPresent()) {
      return new Pull.Item<>(next.get());
    }
    // Empty and terminated look the same in this table, which is the whole reason the agent row
    // carries
    // the mark: an agent terminated while it was busy has nothing waiting, and must not be read
    // as merely idle. Whether it carries that mark is Agents's question, not this table's.
    return agents.terminated(agentType, agent) ? new Pull.Pill<>() : new Pull.Empty<>();
  }

  @Override
  public void append(BacklogItem<I> item) {
    Objects.requireNonNull(item, ITEM_REQUIRED);
    insert(ordinalFrom(NEXT_ORDINAL), item);
  }

  @Override
  public void prepend(BacklogItem<I> item) {
    Objects.requireNonNull(item, ITEM_REQUIRED);
    insert(ordinalFrom(FIRST_ORDINAL), item);
  }

  @Override
  public void replaceAll(BacklogItem<I> item) {
    Objects.requireNonNull(item, ITEM_REQUIRED);
    clear();
    insert(1, item);
  }

  @Override
  public int size() {
    return jdbc.sql(COUNT).params(agentType.value(), agent.value()).query(Integer.class).single();
  }

  @Override
  public void dropOldest(int count) {
    if (count <= 0) {
      return;
    }
    jdbc.sql(DROP_OLDEST).params(agentType.value(), agent.value(), count).update();
  }

  @Override
  public List<BacklogItem<I>> all() {
    return jdbc.sql(ALL).params(agentType.value(), agent.value()).query((rs, _) -> item(rs)).list();
  }

  @Override
  public void rewrite(List<BacklogItem<I>> items) {
    Objects.requireNonNull(items, "items must not be null");
    clear();
    long ordinal = 1;
    for (BacklogItem<I> item : items) {
      insert(ordinal++, item);
    }
  }

  private BacklogItem<I> item(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new BacklogItem<>(
        codec.decode(rs.getBytes("payload")), rs.getTimestamp("arrived_at").toInstant());
  }

  private int clear() {
    return jdbc.sql(CLEAR).params(agentType.value(), agent.value()).update();
  }

  private long ordinalFrom(String sql) {
    return jdbc.sql(sql).params(agentType.value(), agent.value()).query(Long.class).single();
  }

  private void insert(long ordinal, BacklogItem<I> item) {
    jdbc.sql(INSERT)
        .params(
            agentType.value(),
            agent.value(),
            ordinal,
            java.sql.Timestamp.from(item.arrivedAt()),
            codec.encode(item.input()))
        .update();
  }

  /** What an agent is holding, without decoding any of it. */
  public static long size(JdbcClient jdbc, AgentType agentType, AgentId agent) {
    return jdbc.sql(COUNT).params(agentType.value(), agent.value()).query(Long.class).single();
  }

  /** Everything this agent ever had waiting, gone. What forgetting an agent has to include. */
  public static void forget(JdbcClient jdbc, AgentType agentType, AgentId agent) {
    jdbc.sql(CLEAR).params(agentType.value(), agent.value()).update();
  }
}
