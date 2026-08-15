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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.plan.Plan;
import org.jwcarman.nessy.spi.plan.Plan.Status;
import org.jwcarman.nessy.spi.plan.Plan.Task;
import org.jwcarman.nessy.spi.plan.PlanStore;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link JdbcPlanStore} against a real Postgres: round-trip, wholesale replacement, ordering, and
 * bootstrap idempotency. Requires Docker; tagged {@code container} so the offline default build
 * never needs it — the same posture {@link JdbcSummaryStoreTest} takes.
 */
@Testcontainers
@Tag("container")
class JdbcPlanStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  private static DataSource dataSource;

  private PlanStore plans;

  @BeforeAll
  static void nessy_jdbc_test_points_a_data_source_at_the_container() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void a_fresh_store_over_an_empty_table() {
    plans = JdbcPlanStore.create(dataSource);
    truncatePlanTable();
  }

  private void truncatePlanTable() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE nessy_plan");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to truncate nessy_plan between tests", e);
    }
  }

  @Test
  void a_saved_plan_round_trips() {
    ConversationId id = ConversationId.generate();
    Plan plan =
        new Plan(
            List.of(
                new Task("fetch the order history", Status.DONE),
                new Task("summarize the disputes", Status.IN_PROGRESS)));

    plans.save(id, plan);

    assertThat(plans.find(id)).contains(plan);
  }

  @Test
  void a_wholesale_replacement_removes_departed_tasks() {
    ConversationId id = ConversationId.generate();
    plans.save(
        id,
        new Plan(
            List.of(
                new Task("fetch the order history", Status.DONE),
                new Task("summarize the disputes", Status.IN_PROGRESS))));

    Plan replacement = new Plan(List.of(new Task("draft the refund email", Status.PENDING)));
    plans.save(id, replacement);

    assertThat(plans.find(id)).contains(replacement);
  }

  @Test
  void ordering_is_preserved_across_save_and_find() {
    ConversationId id = ConversationId.generate();
    Plan plan =
        new Plan(
            List.of(
                new Task("first", Status.DONE),
                new Task("second", Status.IN_PROGRESS),
                new Task("third", Status.PENDING)));

    plans.save(id, plan);

    assertThat(plans.find(id)).isPresent().get().extracting(Plan::tasks).isEqualTo(plan.tasks());
  }

  @Test
  void saving_an_empty_plan_clears_a_previously_saved_one() {
    ConversationId id = ConversationId.generate();
    plans.save(id, new Plan(List.of(new Task("fetch the order history", Status.DONE))));

    plans.save(id, Plan.empty());

    assertThat(plans.find(id)).isEmpty();
  }

  @Test
  void a_conversation_that_never_saved_a_plan_finds_nothing() {
    ConversationId id = ConversationId.generate();

    Optional<Plan> found = plans.find(id);

    assertThat(found).isEmpty();
  }

  @Test
  void the_schema_bootstrap_is_idempotent() {
    assertThatCode(
            () -> {
              JdbcPlanStore.create(dataSource);
              JdbcPlanStore.create(dataSource);
            })
        .doesNotThrowAnyException();
  }

  /**
   * The thinnest possible {@link DataSource}: a fresh {@link DriverManager} connection per call, no
   * pooling. Sufficient for a test that wants one connection per JDBC operation and nothing
   * fancier; a real deployment supplies its own pooled {@code DataSource} instead.
   */
  private static final class DriverManagerDataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;

    private DriverManagerDataSource(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pass) throws SQLException {
      return DriverManager.getConnection(url, username, pass);
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException("not used by JdbcPlanStoreTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by JdbcPlanStoreTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by JdbcPlanStoreTest");
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException("no java.util.logging parent logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
      throw new UnsupportedOperationException("not used by JdbcPlanStoreTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
