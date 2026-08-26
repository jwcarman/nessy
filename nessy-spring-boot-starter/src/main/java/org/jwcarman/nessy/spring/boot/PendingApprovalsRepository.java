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
package org.jwcarman.nessy.spring.boot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The read side of the pending-approvals projection (watchman spec §1.3): the two questions a page
 * asks.
 *
 * <p>Read-only by construction. Answering an approval is {@code ApprovalDesk}'s job and nobody
 * else's — a row here changes because the fold published a fact, never because someone wrote to it.
 */
public class PendingApprovalsRepository {

  private static final String COLUMNS =
      "computation_id, agent_type, agent_id, call_id, action, request_json::text AS request_json,"
          + " parked_at, answer, reference, note, answered_at";

  private static final String PENDING =
      "SELECT "
          + COLUMNS
          + " FROM nessy_pending_approvals"
          + " WHERE answer IS NULL AND request_json IS NOT NULL"
          + " ORDER BY parked_at ASC";

  private static final String RECENT =
      "SELECT "
          + COLUMNS
          + " FROM nessy_pending_approvals"
          + " WHERE answer IS NOT NULL"
          + " ORDER BY answered_at DESC"
          + " LIMIT ?";

  private static final RowMapper<PendingApproval> ROWS = PendingApprovalsRepository::toApproval;

  private final JdbcTemplate jdbc;

  public PendingApprovalsRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * Everything still waiting for an answer, longest wait first — the page's whole content.
   *
   * <p>A row is only pending once its park's own fact has landed. The stream does not promise
   * commit order, so a row can exist carrying an answer and nothing else; it is not pending, it is
   * a half-written answer, and the {@code request_json IS NOT NULL} filter keeps it off the page
   * until the park catches up.
   */
  public List<PendingApproval> pending() {
    return jdbc.query(PENDING, ROWS);
  }

  /**
   * The last {@code limit} answered approvals, most recently answered first — the audit view.
   *
   * @param limit how many rows at most; must be positive
   */
  public List<PendingApproval> recent(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1");
    }
    return jdbc.query(RECENT, ROWS, limit);
  }

  private static PendingApproval toApproval(ResultSet row, int rowNumber) throws SQLException {
    return new PendingApproval(
        row.getString("computation_id"),
        text(row, "agent_type"),
        text(row, "agent_id"),
        text(row, "call_id"),
        text(row, "action"),
        text(row, "request_json"),
        moment(row, "parked_at"),
        text(row, "answer"),
        text(row, "reference"),
        text(row, "note"),
        moment(row, "answered_at"));
  }

  private static Optional<String> text(ResultSet row, String column) throws SQLException {
    return Optional.ofNullable(row.getString(column));
  }

  private static Optional<Instant> moment(ResultSet row, String column) throws SQLException {
    Timestamp timestamp = row.getTimestamp(column);
    return Optional.ofNullable(timestamp).map(Timestamp::toInstant);
  }
}
