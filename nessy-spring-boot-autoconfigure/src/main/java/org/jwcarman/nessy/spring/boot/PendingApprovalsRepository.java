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
 * The read and write sides of the pending-approvals projection: the questions a page asks, and the
 * rows {@link PendingApprovalsListener} writes as it hears them.
 *
 * <p>Durable on purpose. An in-memory map would be simpler and would lose every waiting question on
 * restart — and an approval that a human has not answered yet is exactly the thing most likely to
 * outlive the process that asked it.
 */
public class PendingApprovalsRepository {

  private static final String COLUMNS =
      "call_id, agent_type, agent_id, tool, action, asked_at, expires_at, reply_token, answer,"
          + " note, answered_at";

  private static final String PENDING =
      "SELECT " + COLUMNS + " FROM nessy_pending_approvals WHERE answer IS NULL ORDER BY asked_at";

  private static final String BY_CALL =
      "SELECT " + COLUMNS + " FROM nessy_pending_approvals WHERE call_id = ?";

  private static final String INSERT =
      "INSERT INTO nessy_pending_approvals (call_id, agent_type, agent_id, tool, action, asked_at,"
          + " expires_at, reply_token) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String REFRESH =
      "UPDATE nessy_pending_approvals SET reply_token = ?, expires_at = ?"
          + " WHERE call_id = ? AND answer IS NULL";

  private static final String EXISTS =
      "SELECT count(*) FROM nessy_pending_approvals WHERE call_id = ?";

  private static final String ANSWER =
      "UPDATE nessy_pending_approvals SET answer = ?, note = ?, answered_at = ?"
          + " WHERE call_id = ? AND answer IS NULL";

  private final JdbcTemplate jdbc;

  public PendingApprovalsRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /** Everything still waiting on a person, oldest first. */
  public List<PendingApproval> pending() {
    return jdbc.query(PENDING, MAPPER);
  }

  /** One row by call id, answered or not. */
  public Optional<PendingApproval> byCallId(String callId) {
    return jdbc.query(BY_CALL, MAPPER, callId).stream().findFirst();
  }

  /**
   * Records a question, and REFRESHES the address if it is asked again.
   *
   * <p>Idempotent by call id, which matters: a recovered turn re-runs the calls it never settled,
   * so the same question is asked again and must not become a second row.
   *
   * <p>But not "do nothing", which is the trap. Re-asking mints a NEW reply token, and a row that
   * kept the old one would show a button that cannot work — the engine rejects the stale address
   * with "not a reply token issued by this engine", and the approval is unanswerable for as long as
   * it stands. Measured, not imagined: it is what a restart did.
   *
   * <p>Only while unanswered. A row someone already decided is history, and a late re-ask must not
   * reopen it.
   */
  public void asked(PendingApproval row) {
    // Refresh-then-insert rather than ON CONFLICT ... DO UPDATE, which is PostgreSQL syntax H2
    // rejects outright — and H2 is what this starter runs on when an application supplied no
    // DataSource, so the vendor form made the default configuration unable to park an approval at
    // all. Measured: "bad SQL grammar" on the first question a person was ever asked.
    //
    // Not a race: one listener follows one agent, and an agent's events are serialized, so there
    // is a single writer per call id.
    int refreshed =
        jdbc.update(REFRESH, row.replyToken(), Timestamp.from(row.expiresAt()), row.callId());
    if (refreshed > 0 || alreadyDecided(row.callId())) {
      return;
    }
    jdbc.update(
        INSERT,
        row.callId(),
        row.agentType(),
        row.agentId(),
        row.tool(),
        row.action(),
        Timestamp.from(row.askedAt()),
        Timestamp.from(row.expiresAt()),
        row.replyToken());
  }

  /** Whether a row exists that the refresh deliberately left alone, because it was answered. */
  private boolean alreadyDecided(String callId) {
    Integer rows = jdbc.queryForObject(EXISTS, Integer.class, callId);
    return rows != null && rows > 0;
  }

  /**
   * Records an answer, if the row is still waiting. A second answer changes nothing — the engine
   * settles a call once, and a late click on a stale page must not overwrite what was decided.
   */
  public void answered(String callId, String answer, String note, Instant when) {
    jdbc.update(ANSWER, answer, note, Timestamp.from(when), callId);
  }

  private static final RowMapper<PendingApproval> MAPPER = PendingApprovalsRepository::map;

  private static PendingApproval map(ResultSet row, int rowNumber) throws SQLException {
    return new PendingApproval(
        row.getString("call_id"),
        row.getString("agent_type"),
        row.getString("agent_id"),
        row.getString("tool"),
        row.getString("action"),
        row.getTimestamp("asked_at").toInstant(),
        row.getTimestamp("expires_at").toInstant(),
        row.getString("reply_token"),
        Optional.ofNullable(row.getString("answer")),
        Optional.ofNullable(row.getString("note")),
        Optional.ofNullable(row.getTimestamp("answered_at")).map(Timestamp::toInstant));
  }
}
