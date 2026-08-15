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
package org.jwcarman.nessy.store.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * The one shared bootstrap seam every {@code create(DataSource, ...)} factory in this module runs
 * through: borrow a connection, resolve the {@link JdbcDialect} at that connection if the caller
 * did not already supply one explicitly (design §2 — resolution happens once per store
 * construction, at the same connection the DDL itself runs over, not per statement), load the
 * matching per-dialect schema resource next to the calling class, and run it.
 *
 * <p>Each fragment {@code schema.split(";")} produces is skipped rather than executed if it is
 * nothing but comment lines: Postgres, MySQL, MariaDB, and SQL Server all tolerate a comment-only
 * {@link Statement#execute} as a harmless no-op, but Oracle's driver does not — it fails loudly
 * with {@code ORA-00900} ("invalid SQL statement"), confirmed live running oracle/*.sql against a
 * real Oracle container. Every schema resource in this module opens with the house license header,
 * whose own trailing {@code ;} is exactly the kind of split point that used to leave a comment-only
 * fragment dangling — this is the fix, not a rule that every schema file must avoid semicolons in
 * its comments (SQL Server's schema resources still must: see their own header for why).
 */
final class JdbcSchemaBootstrap {

  private JdbcSchemaBootstrap() {}

  /** Bootstraps with dialect resolution: see the class javadoc. */
  static JdbcDialect bootstrap(
      DataSource dataSource, Class<?> anchor, String resourceName, String errorContext) {
    return bootstrap(dataSource, anchor, resourceName, null, errorContext);
  }

  /**
   * Bootstraps against an explicitly known {@code dialect}, bypassing {@link
   * JdbcDialect#resolve(java.sql.DatabaseMetaData)} entirely — pass {@code null} to resolve from
   * the borrowed connection instead.
   */
  static JdbcDialect bootstrap(
      DataSource dataSource,
      Class<?> anchor,
      String resourceName,
      JdbcDialect dialect,
      String errorContext) {
    try (Connection connection = dataSource.getConnection()) {
      JdbcDialect resolved =
          dialect != null ? dialect : JdbcDialect.resolve(connection.getMetaData());
      String schema = readSchemaResource(anchor, resolved, resourceName, errorContext);
      try (Statement statement = connection.createStatement()) {
        for (String sql : schema.split(";")) {
          String trimmed = sql.strip();
          if (!trimmed.isEmpty() && !isCommentOnly(trimmed)) {
            statement.execute(trimmed);
          }
        }
      }
      return resolved;
    } catch (SQLException e) {
      throw new IllegalStateException(
          "failed to bootstrap the nessy-store-jdbc " + errorContext + " schema", e);
    }
  }

  private static boolean isCommentOnly(String fragment) {
    return fragment.lines().allMatch(line -> line.isBlank() || line.strip().startsWith("--"));
  }

  private static String readSchemaResource(
      Class<?> anchor, JdbcDialect dialect, String resourceName, String errorContext) {
    String path = dialect.schemaDirectory() + "/" + resourceName;
    try (InputStream in = anchor.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException(
            path + " not found on the classpath next to " + anchor.getSimpleName());
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to read " + errorContext + " schema resource " + path, e);
    }
  }
}
