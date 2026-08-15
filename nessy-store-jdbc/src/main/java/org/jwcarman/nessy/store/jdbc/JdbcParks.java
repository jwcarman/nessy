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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.conversation.Parks;

/**
 * The durable {@link Parks} registry over any of the five databases {@link JdbcDialect} knows
 * (design §2): every wait ever registered, kept for the life of the database rather than the
 * process.
 *
 * <p>Rows live in {@code nessy_parks} (token primary key, conversation id, the parked {@link
 * ToolCall}, the minting agent's name) with an index on {@code conversation_id} for the
 * approval-card read. {@link #park} is idempotent — a duplicate token is allowed to lose its insert
 * race rather than fail (see {@link WriteOnceInsert}), the JDBC-side twin of {@link
 * org.jwcarman.nessy.spi.conversation.InMemoryParks}'s {@code putIfAbsent}, the same at-least-once
 * re-registration tolerance. Entries are never removed by this registry once written: replay
 * protection and "is this call still outstanding" are the fold's own questions, not this registry's
 * (design §5).
 *
 * <p>The constructor alone does not create {@code nessy_parks} — a caller pointing at a database
 * another process already bootstrapped should not pay a DDL round trip on every startup. Use {@link
 * #create(DataSource, ObjectMapper)} to bootstrap and construct in one call; its per-dialect schema
 * resource's guarded-create statements are safe to run more than once. As with {@link
 * JdbcConversationStore}, the dialect is resolved once — at bootstrap for {@code create}, lazily
 * and cached thereafter for the plain constructor — and every {@code create}/constructor pair has
 * an explicit-dialect overload that skips resolution entirely.
 */
public final class JdbcParks implements Parks {

  private final DataSource dataSource;
  private final StateCodec codec;

  /** See {@link JdbcConversationStore#dialect} for the resolve-once-then-cache discipline. */
  private volatile JdbcDialect dialect;

  public JdbcParks(DataSource dataSource, ObjectMapper mapper) {
    this(dataSource, mapper, null);
  }

  /** Bypasses dialect resolution entirely — see the class javadoc. */
  public JdbcParks(DataSource dataSource, ObjectMapper mapper, JdbcDialect dialect) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.codec = new StateCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
    this.dialect = dialect;
  }

  /**
   * Bootstraps {@code parks-schema.sql} against {@code dataSource}, then returns a working
   * registry.
   */
  public static JdbcParks create(DataSource dataSource, ObjectMapper mapper) {
    return create(dataSource, mapper, null);
  }

  /** Bootstraps against an explicitly known {@code dialect} — see the class javadoc. */
  public static JdbcParks create(DataSource dataSource, ObjectMapper mapper, JdbcDialect dialect) {
    JdbcDialect resolved =
        JdbcSchemaBootstrap.bootstrap(
            dataSource, JdbcParks.class, "parks-schema.sql", dialect, "parks");
    return new JdbcParks(dataSource, mapper, resolved);
  }

  @Override
  public void park(Park park) {
    Objects.requireNonNull(park, "park must not be null");
    withConnection(
        connection -> {
          JdbcStatements statements = statementsFor(connection);
          WriteOnceInsert.attempt(
              connection,
              "INSERT INTO nessy_parks (token, conversation_id, "
                  + statements.parkedCallColumn()
                  + ", agent_name) VALUES (?, ?, "
                  + statements.jsonPlaceholder()
                  + ", ?)",
              ps -> {
                ps.setString(1, park.token().value());
                ps.setString(2, park.conversationId().value());
                ps.setString(3, codec.writeToolCall(park.call()));
                ps.setString(4, park.agentName());
              });
          return null;
        });
  }

  @Override
  public Optional<Park> find(ParkToken token) {
    Objects.requireNonNull(token, "token must not be null");
    return withConnection(
        connection -> {
          JdbcStatements statements = statementsFor(connection);
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT conversation_id, "
                      + statements.parkedCallColumn()
                      + ", agent_name FROM nessy_parks WHERE token = ?")) {
            ps.setString(1, token.value());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return Optional.empty();
              }
              ConversationId conversationId = new ConversationId(rs.getString("conversation_id"));
              ToolCall call = codec.readToolCall(rs.getString("call"));
              String agentName = rs.getString("agent_name");
              return Optional.of(new Park(conversationId, token, call, agentName));
            }
          }
        });
  }

  @Override
  public List<Park> forConversation(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return withConnection(
        connection -> {
          JdbcStatements statements = statementsFor(connection);
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT token, "
                      + statements.parkedCallColumn()
                      + ", agent_name FROM nessy_parks WHERE conversation_id = ?")) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
              List<Park> parks = new ArrayList<>();
              while (rs.next()) {
                ParkToken token = new ParkToken(rs.getString("token"));
                ToolCall call = codec.readToolCall(rs.getString("call"));
                String agentName = rs.getString("agent_name");
                parks.add(new Park(id, token, call, agentName));
              }
              return List.copyOf(parks);
            }
          }
        });
  }

  /** See {@link JdbcConversationStore#statementsFor(Connection)}. */
  private JdbcStatements statementsFor(Connection connection) throws SQLException {
    JdbcDialect resolved = dialect;
    if (resolved == null) {
      resolved = JdbcDialect.resolve(connection.getMetaData());
      dialect = resolved;
    }
    return JdbcStatements.forDialect(resolved);
  }

  /**
   * Runs {@code body} on a connection borrowed fresh from the pool; no transaction of its own, one
   * statement, autocommit exactly as the pool hands it back — the same discipline {@code
   * JdbcConversationStore}'s own {@code withConnection} follows, and for the same reason: a pool
   * that does not reset a connection between borrowers must never be handed back one still in a
   * prior caller's transaction state.
   */
  private <T> T withConnection(SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return body.apply(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc parks operation failed", e);
    }
  }

  @FunctionalInterface
  private interface SqlFunction<A, T> {
    T apply(A input) throws SQLException;
  }
}
