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
package org.jwcarman.nessy.examples.watchman;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jwcarman.continuum.jdbc.JdbcContinuumRepository;
import org.jwcarman.nessy.spring.boot.PendingApprovals;
import org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The one Postgres the Boot-and-database tests share, and the three shipped schemas applied to it.
 *
 * <p>Why a real database rather than the in-memory stores: the pending-approvals projection IS a
 * table. Asserting "the approval is on the page" against in-memory stores would be asserting
 * something else — the page reads {@code PendingApprovalsRepository}, the starter only declares it
 * beside a {@code DataSource}, and the whole point of these tests is the path a human's browser
 * takes.
 *
 * <p>Untagged and started once for the JVM, the same way {@code DurableResumeTest} and {@code
 * StarterOnPostgresTest} do it. The image is the glibc one, never {@code -alpine} — see {@code
 * JdbcSubstrateContractTest} for why.
 */
final class WatchmanPostgres {

  private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>("postgres:17");

  static {
    CONTAINER.start();
  }

  private WatchmanPostgres() {}

  /**
   * A {@code DataSource} over the shared container with every shipped schema already applied.
   *
   * <p>Applied here rather than in a {@code @BeforeAll}: a Boot context is built lazily on first
   * use, and the harness's pumps start querying the moment it is.
   */
  static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(CONTAINER.getJdbcUrl());
    dataSource.setUser(CONTAINER.getUsername());
    dataSource.setPassword(CONTAINER.getPassword());
    applySchemas(dataSource);
    return dataSource;
  }

  private static void applySchemas(DataSource dataSource) {
    String substrate = resource(JdbcSubstrate.class, "nessy-postgresql.sql");
    String continuum = resource(JdbcContinuumRepository.class, "continuum-postgresql.sql");
    String projection = resource(PendingApprovals.class, "pending-approvals-postgresql.sql");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(substrate);
      statement.execute(continuum);
      statement.execute(projection);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to apply the shipped schemas", e);
    }
  }

  private static String resource(Class<?> beside, String name) {
    try (InputStream in = beside.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException(name + " not found beside " + beside.getName());
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
