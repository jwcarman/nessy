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
package org.jwcarman.nessy.memory.summarizing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The summary sidecar, and the only thing that touches it.
 *
 * <p>One row per agent, and only once there is something to put in it. {@code covers_through} is
 * the transcript sequence the summary accounts for; everything after it is still carried verbatim.
 */
final class Summaries {

  private static final String WHERE_AGENT = " WHERE agent_type = ? AND agent_id = ?";
  private static final String READ =
      "SELECT covers_through, summary FROM nessy_summary" + WHERE_AGENT;

  /**
   * Only ever moves FORWARD.
   *
   * <p>Two writes can race — a second process, or two threads that both tripped the threshold — and
   * both may produce a paragraph. The condition means whichever covers less loses, so the cost of
   * that race is a wasted model call rather than a summary going backwards and re-reading history
   * it had already compressed.
   */
  private static final String ADVANCE =
      "UPDATE nessy_summary SET covers_through = ?, summary = ?, updated_at = ?"
          + WHERE_AGENT
          + " AND covers_through < ?";

  private static final String START =
      "INSERT INTO nessy_summary (agent_type, agent_id, covers_through, summary, updated_at)"
          + " VALUES (?, ?, ?, ?, ?)";
  private static final String DELETE = "DELETE FROM nessy_summary" + WHERE_AGENT;

  /** One agent's compressed history, and how far it reaches. */
  record Summary(long coversThrough, String text) {}

  private final JdbcClient jdbc;

  Summaries(DataSource dataSource) {
    this.jdbc =
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
  }

  Optional<Summary> find(AgentType agentType, AgentId agentId) {
    return jdbc.sql(READ)
        .params(agentType.name(), agentId.value())
        .query(
            (row, number) -> new Summary(row.getLong("covers_through"), row.getString("summary")))
        .optional();
  }

  /**
   * Records a summary, if it covers more than whatever is already there.
   *
   * <p>Update-then-insert rather than vendor upsert syntax, so one statement set serves every
   * database — the same shape {@code PendingApprovalsRepository} uses, for the same reason.
   */
  void advance(
      AgentType agentType, AgentId agentId, long coversThrough, String summary, Instant now) {
    int updated =
        jdbc.sql(ADVANCE)
            .params(
                coversThrough,
                summary,
                Timestamp.from(now),
                agentType.name(),
                agentId.value(),
                coversThrough)
            .update();
    if (updated == 0) {
      try {
        jdbc.sql(START)
            .params(agentType.name(), agentId.value(), coversThrough, summary, Timestamp.from(now))
            .update();
      } catch (org.springframework.dao.DuplicateKeyException _) {
        // Somebody inserted between the update and this insert, and their summary covers at least
        // as much as ours. Theirs stands: a summary is a summary.
      }
    }
  }

  void forget(AgentType agentType, AgentId agentId) {
    jdbc.sql(DELETE).params(agentType.name(), agentId.value()).update();
  }
}
