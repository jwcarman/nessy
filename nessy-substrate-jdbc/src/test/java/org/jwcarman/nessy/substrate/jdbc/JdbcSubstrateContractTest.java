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
package org.jwcarman.nessy.substrate.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.testing.SubstrateContract;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Certifies {@link JdbcSubstrate} against {@link SubstrateContract}, running the shipped DDL
 * resource — never a pasted copy — against a real PostgreSQL so the file a user copies into their
 * own migration tooling is the file that is proven.
 */
@Testcontainers
class JdbcSubstrateContractTest extends SubstrateContract {

  // Deliberately not the -alpine image: musl's strcoll degenerates to a byte-order strcmp, so
  // alpine hides collation-dependent ordering bugs that a glibc PostgreSQL (Debian, RDS, and
  // essentially every production deployment) would expose. Certifying against the image whose
  // libc masks this class of defect would be worse than not certifying at all.
  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

  @Override
  protected Substrate createSubstrate() {
    DataSource dataSource = dataSource();
    applyShippedSchema(dataSource);
    truncate(dataSource);
    return new JdbcSubstrate(dataSource);
  }

  private DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private void applyShippedSchema(DataSource dataSource) {
    String ddl = readShippedSchema();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(ddl);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to apply shipped schema", e);
    }
  }

  private String readShippedSchema() {
    try (InputStream in = JdbcSubstrate.class.getResourceAsStream("nessy-postgresql.sql")) {
      if (in == null) {
        throw new IllegalStateException("nessy-postgresql.sql not found on the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void truncate(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE TABLE nessy_document, nessy_journal");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to truncate shipped schema", e);
    }
  }
}
