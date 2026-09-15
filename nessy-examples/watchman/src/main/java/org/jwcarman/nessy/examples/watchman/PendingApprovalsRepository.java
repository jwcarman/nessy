package org.jwcarman.nessy.examples.watchman;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.CallId;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The board's table. See {@code watchman-schema.sql} for what it is and what it is not.
 *
 * <p>Instants cross as {@link OffsetDateTime}: PostgreSQL refuses a bare {@link Instant} rather
 * than guess which of its date types was meant.
 */
public class PendingApprovalsRepository {

  private static final String COLUMNS =
      "call_id, agent_type, agent_id, tool, action, asked_at, expires_at, reply_token, answer,"
          + " note, answered_at";

  private static final String PENDING =
      "SELECT "
          + COLUMNS
          + " FROM watchman_pending_approval WHERE answer IS NULL ORDER BY asked_at";

  private static final String BY_CALL =
      "SELECT "
          + COLUMNS
          + " FROM watchman_pending_approval"
          + " WHERE agent_type = ? AND agent_id = ? AND call_id = ?";

  private static final String INSERT =
      "INSERT INTO watchman_pending_approval (agent_type, agent_id, call_id, tool, action,"
          + " asked_at, expires_at, reply_token) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

  // A recovered turn asks again, with a fresh token and deadline: the row it wrote before is
  // brought up to date rather than duplicated, and one already answered is left alone.
  private static final String REFRESH =
      "UPDATE watchman_pending_approval SET reply_token = ?, expires_at = ?"
          + " WHERE agent_type = ? AND agent_id = ? AND call_id = ? AND answer IS NULL";

  private static final String EXISTS =
      "SELECT count(*) FROM watchman_pending_approval"
          + " WHERE agent_type = ? AND agent_id = ? AND call_id = ?";

  private static final String ANSWER =
      "UPDATE watchman_pending_approval SET answer = ?, note = ?, answered_at = ?"
          + " WHERE agent_type = ? AND agent_id = ? AND call_id = ? AND answer IS NULL";

  private final JdbcClient jdbc;

  public PendingApprovalsRepository(DataSource dataSource) {
    this.jdbc =
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
  }

  /** Creates the board's table if it is not there. Ours to run, because the table is ours. */
  public static void initialize(DataSource dataSource) {
    new ResourceDatabasePopulator(new ClassPathResource("watchman-schema.sql")).execute(dataSource);
  }

  public List<PendingApproval> pending() {
    return jdbc.sql(PENDING).query(MAPPER).list();
  }

  public Optional<PendingApproval> byCallId(AgentType agentType, AgentId agentId, CallId callId) {
    return jdbc.sql(BY_CALL)
        .params(agentType.value(), key(agentId), callId.value())
        .query(MAPPER)
        .optional();
  }

  public void asked(PendingApproval row) {
    int refreshed =
        jdbc.sql(REFRESH)
            .params(
                row.replyToken(),
                utc(row.expiresAt()),
                row.agentType().value(),
                key(row.agentId()),
                row.callId().value())
            .update();
    if (refreshed > 0 || alreadyDecided(row.agentType(), row.agentId(), row.callId())) {
      return;
    }
    jdbc.sql(INSERT)
        .params(
            row.agentType().value(),
            key(row.agentId()),
            row.callId().value(),
            row.tool(),
            row.action(),
            utc(row.askedAt()),
            utc(row.expiresAt()),
            row.replyToken())
        .update();
  }

  private boolean alreadyDecided(AgentType agentType, AgentId agentId, CallId callId) {
    return jdbc.sql(EXISTS)
            .params(agentType.value(), key(agentId), callId.value())
            .query(Long.class)
            .single()
        > 0;
  }

  public void answered(
      AgentType agentType,
      AgentId agentId,
      CallId callId,
      String answer,
      String note,
      Instant when) {
    jdbc.sql(ANSWER)
        .params(answer, note, utc(when), agentType.value(), key(agentId), callId.value())
        .update();
  }

  private static final RowMapper<PendingApproval> MAPPER = PendingApprovalsRepository::map;

  private static PendingApproval map(ResultSet row, int rowNumber) throws SQLException {
    return new PendingApproval(
        new CallId(row.getString("call_id")),
        new AgentType(row.getString("agent_type")),
        new AgentId(UUID.fromString(row.getString("agent_id"))),
        row.getString("tool"),
        row.getString("action"),
        row.getObject("asked_at", OffsetDateTime.class).toInstant(),
        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
        row.getString("reply_token"),
        Optional.ofNullable(row.getString("answer")),
        Optional.ofNullable(row.getString("note")),
        Optional.ofNullable(row.getObject("answered_at", OffsetDateTime.class))
            .map(OffsetDateTime::toInstant));
  }

  /** The id as the TEXT column holds it; a bare UUID is not text to PostgreSQL. */
  private static String key(AgentId agentId) {
    return agentId.value().toString();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
