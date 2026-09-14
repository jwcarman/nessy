package org.jwcarman.nessy.engine.store;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes agent state rows.
 *
 * <p><b>Hand-written over {@link JdbcClient} rather than a Spring Data repository.</b> A repository
 * is a proxy and a proxy needs an {@code ApplicationContext}, which would make this module a
 * framework rather than a library — and the engine is meant to be usable with nothing but a {@code
 * DataSource}. The three statements below are the whole of what the repository was giving us.
 *
 * <p>The version column is maintained here for the same reason. Spring Data incremented it on save
 * and refused a save whose version had moved underneath; that check is now the {@code WHERE version
 * = ?} clause, which is the same optimistic lock written out in the one place that depends on it.
 */
public class AgentStateRepository {

  private static final String LOCK =
      """
      SELECT agent_id, agent_type, version, state_type, payload, updated_at
        FROM nessy_agent_state
       WHERE agent_id = ?
         FOR UPDATE
      """;

  private static final String INSERT =
      """
      INSERT INTO nessy_agent_state
             (agent_id, agent_type, version, state_type, payload, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
      """;

  private static final String UPDATE =
      """
      UPDATE nessy_agent_state
         SET version = ?, state_type = ?, payload = ?, updated_at = ?
       WHERE agent_id = ? AND version = ?
      """;

  private final JdbcClient jdbc;

  public AgentStateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Loads an agent's state under a row lock, blocking until any transition in flight for that agent
   * commits. This is the serialization primitive the whole design rests on: {@code SELECT ... FOR
   * UPDATE} on one row, which leaves every other agent free to proceed.
   *
   * <p>Must be called inside a transaction; the lock is released when it ends.
   */
  public Optional<AgentStateRow> findAndLockByAgentId(UUID agentId) {
    return jdbc.sql(LOCK)
        .params(agentId)
        .query(
            (rs, n) ->
                new AgentStateRow(
                    rs.getObject("agent_id", UUID.class),
                    rs.getString("agent_type"),
                    rs.getLong("version"),
                    rs.getString("state_type"),
                    rs.getBytes("payload"),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  /**
   * Writes a row and hands back what was written, version included.
   *
   * <p>Version zero means a row that has never been saved, exactly as it did before: an insert
   * lands at one, and every later save moves it on by one. Returning the saved row rather than void
   * is what lets a caller see the version it now holds, which is where the next entry's seq comes
   * from.
   */
  public AgentStateRow save(AgentStateRow row) {
    AgentStateRow next = row.atVersion(row.version() + 1);
    int written =
        row.version() == 0
            ? jdbc.sql(INSERT)
                .params(
                    next.agentId(),
                    next.agentType(),
                    next.version(),
                    next.stateType(),
                    next.payload(),
                    utc(next.updatedAt()))
                .update()
            : jdbc.sql(UPDATE)
                .params(
                    next.version(),
                    next.stateType(),
                    next.payload(),
                    utc(next.updatedAt()),
                    next.agentId(),
                    row.version())
                .update();
    if (written != 1) {
      // Somebody moved this agent while we held what we thought was its current version. Under the
      // row lock that cannot happen; outside one it is exactly what must not pass silently, because
      // the fold that produced this state read a version that no longer stands.
      throw new IllegalStateException(
          "agent %s was modified concurrently at version %d"
              .formatted(row.agentId(), row.version()));
    }
    return next;
  }

  /**
   * PostgreSQL will not accept a bare {@link Instant} as a parameter — it refuses rather than
   * guessing which of its date types was meant.
   */
  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
