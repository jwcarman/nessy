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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link Chapters} over rows in {@code nessy_chapter}.
 *
 * <p><b>One statement to append.</b> No transaction is available here, so the whole of {@link
 * #append} is a single SQL statement, and the database serialises the callers that race. The guard
 * on the first chapter rejects a caller whose {@code after} is already stale. The guard alone is
 * not enough: it reads a snapshot, so eight callers that all see the same end all pass it, and
 * their chapters have different {@code from_turn}s, so the primary key lets them all in. What stops
 * them is {@code after_turn}, the end of the chapter before, which is unique per agent: a second
 * caller claiming the same predecessor waits on the first's index entry and, once the first
 * commits, stores nothing.
 *
 * <p>A chapter after the first is stored only if the one before it was, so a call stores all of its
 * chapters or none. If a later chapter is still refused, by a key the guard cannot see, what the
 * call stored is deleted again.
 */
public class JdbcChapters implements Chapters {

  // The first chapter stores only if the closed chapters still end at the point the caller named.
  private static final String GUARDED_ROW =
      """
      INSERT INTO nessy_chapter
             (agent_type, agent_id, from_turn, through_turn, after_turn, closed_at)
      SELECT ?, ?, ?, ?, ?, now()
       WHERE (SELECT COALESCE(MAX(through_turn), 0) FROM nessy_chapter
               WHERE agent_type = ? AND agent_id = ?) = ?
      ON CONFLICT DO NOTHING
      RETURNING 1
      """;

  // Every later chapter stores only if the one before it did.
  private static final String FOLLOWING_ROW =
      """
      INSERT INTO nessy_chapter
             (agent_type, agent_id, from_turn, through_turn, after_turn, closed_at)
      SELECT ?, ?, ?, ?, ?, now()
       WHERE EXISTS (SELECT 1 FROM %s)
      ON CONFLICT DO NOTHING
      RETURNING 1
      """;

  private static final String DELETE_MINE =
      """
      DELETE FROM nessy_chapter
       WHERE agent_type = ? AND agent_id = ? AND from_turn = ? AND through_turn = ?
         AND after_turn = ? AND summary IS NULL
      """;

  private static final String SELECT_END =
      "SELECT MAX(through_turn) FROM nessy_chapter WHERE agent_type = ? AND agent_id = ?";

  private static final String SUMMARIZE =
      """
      UPDATE nessy_chapter SET summary = ?, summarized_at = now()
       WHERE agent_type = ? AND agent_id = ? AND from_turn = ? AND through_turn = ?
         AND summary IS NULL
      """;

  private static final String UNSUMMARIZED =
      """
      SELECT from_turn, through_turn FROM nessy_chapter
       WHERE agent_type = ? AND agent_id = ? AND summary IS NULL
       ORDER BY from_turn
      """;

  private static final String SUMMARIES =
      """
      SELECT from_turn, through_turn, summary FROM nessy_chapter
       WHERE agent_type = ? AND agent_id = ? AND summary IS NOT NULL
         AND from_turn < COALESCE((SELECT MIN(from_turn) FROM nessy_chapter
                                    WHERE agent_type = ? AND agent_id = ? AND summary IS NULL),
                                  9223372036854775807)
       ORDER BY from_turn
      """;

  private final JdbcClient jdbc;

  public JdbcChapters(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  public JdbcChapters(DataSource dataSource) {
    this(JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null")));
  }

  @Override
  public boolean append(
      AgentType type, AgentId agent, Optional<TurnId> after, List<Chapter> chapters) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(after, "after must not be null");
    Objects.requireNonNull(chapters, "chapters must not be null");
    validate(type, agent, after, chapters);
    if (chapters.isEmpty()) {
      return true;
    }
    // One statement, so the database decides. Each chapter is a data-modifying CTE that stores
    // only if the one before it did, and the first only if the closed chapters still end at
    // `after`; the unique after_turn makes two writers claiming the same predecessor collide
    // on the index, and the loser's ON CONFLICT DO NOTHING waits for the winner to commit and then
    // stores nothing.
    long afterValue = after.map(TurnId::value).orElse(0L);
    StringBuilder sql = new StringBuilder("WITH ");
    List<Object> params = new ArrayList<>();
    long previous = afterValue;
    for (int i = 0; i < chapters.size(); i++) {
      Chapter chapter = chapters.get(i);
      if (i > 0) {
        sql.append(", ");
      }
      sql.append('c').append(i).append(" AS (");
      sql.append(i == 0 ? GUARDED_ROW : FOLLOWING_ROW.formatted("c" + (i - 1)));
      sql.append(')');
      params.add(type.value());
      params.add(agent.value());
      params.add(chapter.from().value());
      params.add(chapter.through().value());
      params.add(previous);
      if (i == 0) {
        params.add(type.value());
        params.add(agent.value());
        params.add(afterValue);
      }
      previous = chapter.through().value();
    }
    sql.append(" SELECT ");
    for (int i = 0; i < chapters.size(); i++) {
      sql.append(i == 0 ? "" : " + ").append("(SELECT count(*) FROM c").append(i).append(')');
    }
    long stored = jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    if (stored == chapters.size()) {
      return true;
    }
    if (stored > 0) {
      // Only a later chapter's own key can have refused it while the first stored, and then the
      // list is not stored whole. Taking back exactly what this call wrote leaves none of it.
      undo(type, agent, afterValue, chapters);
    }
    return false;
  }

  private void undo(AgentType type, AgentId agent, long afterValue, List<Chapter> chapters) {
    long previous = afterValue;
    for (Chapter chapter : chapters) {
      jdbc.sql(DELETE_MINE)
          .params(
              type.value(),
              agent.value(),
              chapter.from().value(),
              chapter.through().value(),
              previous)
          .update();
      previous = chapter.through().value();
    }
  }

  private static void validate(
      AgentType type, AgentId agent, Optional<TurnId> after, List<Chapter> chapters) {
    TurnId previous = after.orElse(null);
    for (Chapter chapter : chapters) {
      if (!chapter.agentType().equals(type) || !chapter.agentId().equals(agent)) {
        throw new IllegalArgumentException(
            "chapter %s through %s is not for agent %s/%s"
                .formatted(chapter.from(), chapter.through(), type, agent));
      }
      if (previous != null && chapter.from().value() <= previous.value()) {
        throw new IllegalArgumentException(
            "chapter from %s does not come after turn %s".formatted(chapter.from(), previous));
      }
      previous = chapter.through();
    }
  }

  @Override
  public boolean summarize(Summary summary) {
    Objects.requireNonNull(summary, "summary must not be null");
    Chapter chapter = summary.chapter();
    return jdbc.sql(SUMMARIZE)
            .params(
                summary.text(),
                chapter.agentType().value(),
                chapter.agentId().value(),
                chapter.from().value(),
                chapter.through().value())
            .update()
        == 1;
  }

  @Override
  public Optional<TurnId> closedThrough(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return jdbc.sql(SELECT_END)
        .params(type.value(), agent.value())
        .query((rs, _) -> rs.getLong(1))
        .optional()
        .filter(through -> through > 0)
        .map(TurnId::new);
  }

  @Override
  public List<Chapter> unsummarized(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return new ArrayList<>(
        jdbc.sql(UNSUMMARIZED)
            .params(type.value(), agent.value())
            .query(
                (rs, _) ->
                    new Chapter(
                        type,
                        agent,
                        new TurnId(rs.getLong("from_turn")),
                        new TurnId(rs.getLong("through_turn"))))
            .list());
  }

  @Override
  public List<Summary> summaries(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return jdbc.sql(SUMMARIES)
        .params(type.value(), agent.value(), type.value(), agent.value())
        .query(
            (rs, _) ->
                new Summary(
                    new Chapter(
                        type,
                        agent,
                        new TurnId(rs.getLong("from_turn")),
                        new TurnId(rs.getLong("through_turn"))),
                    rs.getString("summary")))
        .list();
  }
}
