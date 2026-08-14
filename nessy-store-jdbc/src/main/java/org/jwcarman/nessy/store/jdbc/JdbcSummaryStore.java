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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.memory.SummaryStore;

/**
 * The durable {@link SummaryStore}: one row per conversation in {@code nessy_summary}, last write
 * wins.
 *
 * <p>{@link #save} is an upsert (Postgres {@code ON CONFLICT (conversation_id) DO UPDATE}) — there
 * is no fencing (design §10), so a save simply replaces whatever watermark and text the row held
 * before. A lost or clobbered write is never lost words: the transcript is the truth a summary is
 * only ever a cheaper way to re-read, so the worst a lost write costs is one re-summarized tail on
 * the next recall.
 *
 * <p>The constructor alone does not create {@code nessy_summary} — a caller pointing at a database
 * another process already bootstrapped should not pay a DDL round trip on every startup. Use {@link
 * #create(DataSource)} to bootstrap and construct in one call; its {@code CREATE TABLE IF NOT
 * EXISTS} is safe to run more than once.
 */
public final class JdbcSummaryStore implements SummaryStore {

  private final DataSource dataSource;

  public JdbcSummaryStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
  }

  /**
   * Bootstraps {@code summary-schema.sql} against {@code dataSource}, then returns a working store.
   */
  public static JdbcSummaryStore create(DataSource dataSource) {
    JdbcSummaryStore store = new JdbcSummaryStore(dataSource);
    store.bootstrap();
    return store;
  }

  private void bootstrap() {
    String schema = readSchemaResource();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : schema.split(";")) {
        String trimmed = sql.strip();
        if (!trimmed.isEmpty()) {
          statement.execute(trimmed);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to bootstrap the nessy-store-jdbc summary schema", e);
    }
  }

  private static String readSchemaResource() {
    try (InputStream in = JdbcSummaryStore.class.getResourceAsStream("summary-schema.sql")) {
      if (in == null) {
        throw new IllegalStateException(
            "summary-schema.sql not found on the classpath next to JdbcSummaryStore");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read summary-schema.sql", e);
    }
  }

  @Override
  public Optional<Summary> find(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return withConnection(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT watermark, summary FROM nessy_summary WHERE conversation_id = ?")) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return Optional.empty();
              }
              return Optional.of(new Summary(rs.getLong("watermark"), rs.getString("summary")));
            }
          }
        });
  }

  @Override
  public void save(ConversationId id, Summary summary) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
    withConnection(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "INSERT INTO nessy_summary (conversation_id, watermark, summary) VALUES (?, ?, ?)"
                      + " ON CONFLICT (conversation_id)"
                      + " DO UPDATE SET watermark = EXCLUDED.watermark, summary = EXCLUDED.summary")) {
            ps.setString(1, id.value());
            ps.setLong(2, summary.watermark());
            ps.setString(3, summary.text());
            ps.executeUpdate();
          }
          return null;
        });
  }

  /**
   * Runs {@code body} on a connection borrowed fresh from the pool; no transaction of its own, one
   * statement, autocommit exactly as the pool hands it back — the same discipline the other two
   * doors' own {@code withConnection} follows, and for the same reason: a pool that does not reset
   * a connection between borrowers must never be handed back one still in a prior caller's
   * transaction state.
   */
  private <T> T withConnection(SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return body.apply(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc summary store operation failed", e);
    }
  }

  @FunctionalInterface
  private interface SqlFunction<A, T> {
    T apply(A input) throws SQLException;
  }
}
