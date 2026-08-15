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
package org.jwcarman.nessy.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.plan.Plan;
import org.jwcarman.nessy.spi.plan.Plan.Status;
import org.jwcarman.nessy.spi.plan.Plan.Task;
import org.jwcarman.nessy.spi.plan.PlanStore;

/**
 * The durable {@link PlanStore} over any of the five databases {@link JdbcDialect} knows (design
 * §2): one row per task in {@code nessy_plan}, wholesale replacement on every {@link #save}.
 *
 * <p>Unlike {@link JdbcSummaryStore}, this store's runtime SQL ({@link #DELETE_SQL}, {@link
 * #INSERT_SQL}, {@link #FIND_SQL}) is dialect-IDENTICAL — no upsert, no vendor-specific cast, no
 * per-dialect variant needed. A {@link JdbcDialect} matters here only to {@link #create(DataSource,
 * JdbcDialect)}, which needs it to pick the right {@code plan-schema.sql} resource to bootstrap;
 * this store never resolves or caches a dialect of its own, and the plain constructor does not
 * accept one. Every runtime operation below runs the same three constants regardless of which of
 * the five databases backs it.
 *
 * <p>{@link #save} replaces a conversation's whole plan in one transaction: {@code DELETE} every
 * existing row for the conversation, then a batched {@code INSERT} of the new rows in ordinal order
 * (design §3.2, §4). There is no fencing and no retry here, unlike the durable inbox drain this
 * store's batching otherwise resembles: the sole writer is the {@code update_plan} tool, executing
 * inside the agent loop, which runs one turn at a time per conversation, so two concurrent saves of
 * the same plan never race in practice. An at-least-once replay of that same tool call redoes the
 * identical {@code DELETE}+{@code INSERT} and lands on the identical rows — re-done work, never a
 * lost or corrupted plan — so there is nothing here for a fencing token or a retry loop to protect
 * against.
 *
 * <p>The constructor alone does not create {@code nessy_plan} — a caller pointing at a database
 * another process already bootstrapped should not pay a DDL round trip on every startup. Use {@link
 * #create(DataSource)} to bootstrap and construct in one call; its per-dialect schema resource's
 * guarded-create statement is safe to run more than once. {@link #create(DataSource, JdbcDialect)}
 * accepts an explicit dialect for a caller that already knows it (or a driver whose metadata lies),
 * bypassing {@link JdbcDialect#resolve} entirely for the bootstrap step; {@code null} there means
 * resolve, same as {@link #create(DataSource)}. Once bootstrap is done — or skipped entirely, via
 * the plain constructor — this store has no further use for a dialect.
 */
public final class JdbcPlanStore implements PlanStore {

  private static final String DELETE_SQL = "DELETE FROM nessy_plan WHERE conversation_id = ?";

  private static final String INSERT_SQL =
      "INSERT INTO nessy_plan (conversation_id, ordinal, title, status) VALUES (?, ?, ?, ?)";

  private static final String FIND_SQL =
      "SELECT title, status FROM nessy_plan WHERE conversation_id = ? ORDER BY ordinal";

  private final DataSource dataSource;

  public JdbcPlanStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
  }

  /**
   * Bootstraps {@code plan-schema.sql} against {@code dataSource}, then returns a working store.
   */
  public static JdbcPlanStore create(DataSource dataSource) {
    return create(dataSource, null);
  }

  /** Bootstraps against an explicitly known {@code dialect} — see the class javadoc. */
  public static JdbcPlanStore create(DataSource dataSource, JdbcDialect dialect) {
    JdbcSchemaBootstrap.bootstrap(
        dataSource, JdbcPlanStore.class, "plan-schema.sql", dialect, "plan");
    return new JdbcPlanStore(dataSource);
  }

  @Override
  public Optional<Plan> find(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return withConnection(
        connection -> {
          List<Task> tasks = new ArrayList<>();
          try (PreparedStatement ps = connection.prepareStatement(FIND_SQL)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                tasks.add(new Task(rs.getString("title"), Status.valueOf(rs.getString("status"))));
              }
            }
          }
          if (tasks.isEmpty()) {
            return Optional.empty();
          }
          return Optional.of(new Plan(tasks));
        });
  }

  /**
   * Replaces {@code id}'s whole plan in one transaction: {@code DELETE} every existing row, then a
   * batched {@code INSERT} of {@code plan}'s tasks in ordinal order, committed together. See the
   * class javadoc for why this needs no fencing and no retry.
   */
  @Override
  public void save(ConversationId id, Plan plan) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    withConnection(
        connection -> {
          try {
            connection.setAutoCommit(false);
            deleteExisting(connection, id);
            insertAll(connection, id, plan);
            connection.commit();
            return null;
          } catch (SQLException | RuntimeException e) {
            rollbackQuietly(connection, e);
            throw e;
          } finally {
            restoreAutoCommit(connection);
          }
        });
  }

  private void deleteExisting(Connection connection, ConversationId id) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(DELETE_SQL)) {
      ps.setString(1, id.value());
      ps.executeUpdate();
    }
  }

  private void insertAll(Connection connection, ConversationId id, Plan plan) throws SQLException {
    List<Task> tasks = plan.tasks();
    if (tasks.isEmpty()) {
      return;
    }
    try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
      for (int ordinal = 0; ordinal < tasks.size(); ordinal++) {
        Task task = tasks.get(ordinal);
        ps.setString(1, id.value());
        ps.setInt(2, ordinal);
        ps.setString(3, task.title());
        ps.setString(4, task.status().name());
        ps.addBatch();
      }
      checkBatchResults(ps.executeBatch(), id);
    }
  }

  /**
   * {@code executeBatch()} is not obligated to throw on a failed element: the JDBC spec also
   * permits a driver to continue past one and report {@link Statement#EXECUTE_FAILED} (a negative
   * count other than {@link Statement#SUCCESS_NO_INFO}) for that element in the array it returns,
   * with the batch call itself returning normally. Left unchecked, that path would commit a plan
   * with silently missing rows. Scanning the counts here turns that silent partial failure into a
   * thrown {@link SQLException} naming the failing index, which rolls back this save's transaction.
   */
  private static void checkBatchResults(int[] counts, ConversationId id) throws SQLException {
    for (int index = 0; index < counts.length; index++) {
      int count = counts[index];
      if (count < 0 && count != Statement.SUCCESS_NO_INFO) {
        throw new SQLException(
            "plan save batch reported a failed insert at index "
                + index
                + " for conversation "
                + id.value()
                + " without throwing");
      }
    }
  }

  /** Rolls back, folding a rollback failure into {@code cause} rather than losing either one. */
  private static void rollbackQuietly(Connection connection, Exception cause) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      cause.addSuppressed(rollbackFailure);
    }
  }

  /**
   * Best-effort: puts the connection's autocommit back to {@code true} before it returns to the
   * pool. A failure here is swallowed rather than thrown — throwing from this {@code finally} would
   * replace whatever exception is already propagating from {@link #save}'s transaction body, and a
   * connection too broken to restore is the pool's own problem to evict on next borrow, not this
   * method's to escalate. Mirrors {@link JdbcConversationStore#restoreConnection}.
   */
  private static void restoreAutoCommit(Connection connection) {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException _) {
      // best-effort restore; see method javadoc
    }
  }

  /**
   * Runs {@code body} on a connection borrowed fresh from the pool; no transaction of its own
   * beyond what {@code body} opens explicitly, autocommit exactly as the pool hands it back before
   * {@code body} runs — the same discipline the other doors' own {@code withConnection} follows,
   * and for the same reason: a pool that does not reset a connection between borrowers must never
   * be handed back one still in a prior caller's transaction state.
   */
  private <T> T withConnection(SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return body.apply(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc plan store operation failed", e);
    }
  }

  @FunctionalInterface
  private interface SqlFunction<A, T> {
    T apply(A input) throws SQLException;
  }
}
