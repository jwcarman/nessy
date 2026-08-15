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
package org.jwcarman.nessy.store.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.memory.Transcript;

/**
 * The durable transcript: an append-only, versioned, per-conversation message log in Cassandra —
 * {@code nessy-store-jdbc}'s sibling, {@link CqlSession} in place of {@code DataSource}.
 *
 * <p>Every telling lands in {@code nessy_transcript}, one row per message, clustered by an
 * append-only {@code version} column (design §2). Cassandra has no row lock and no sequence, so
 * {@link #append} cannot serialize concurrent writers the way {@code JdbcTranscript} does with
 * {@code SELECT ... FOR UPDATE}; it instead compare-and-inserts in a loop (design §3). Each attempt
 * reads the partition's last row at {@link DefaultConsistencyLevel#SERIAL} — a linearized read that
 * also finishes any Paxos round left in flight by a previous attempt, so it never observes a
 * half-committed write — and, unless that row's message already equals the incoming one (the
 * no-stutter rule, held exactly as the JDBC sibling holds it), issues an {@code INSERT ... IF NOT
 * EXISTS} at the next version. If another writer's insert won that version instead, this attempt's
 * insert is simply not applied; the loop re-reads and re-evaluates the stutter rule against the
 * winner's message, which is the same serialization the row lock gives {@code JdbcTranscript} for
 * free. A writer that keeps losing this race for {@value #MAX_APPEND_ATTEMPTS} attempts in a row
 * gives up loudly with an {@link IllegalStateException} naming the contention, rather than spinning
 * forever.
 *
 * <p>The constructor alone does not create {@code nessy_transcript} — a caller pointing at a
 * keyspace another process already bootstrapped should not pay a DDL round trip on every startup.
 * Use {@link #create(CqlSession, ObjectMapper)} to bootstrap and construct in one call; its {@code
 * CREATE TABLE IF NOT EXISTS} is safe to run more than once. The keyspace itself is the session's
 * business — this class never creates or selects one, exactly as the JDBC store never creates the
 * database.
 */
public final class CassandraTranscript implements Transcript {

  private static final String ID_MUST_NOT_BE_NULL = "id must not be null";

  /** Bounded in the spirit of {@code ConversationLoop}'s own {@code MAX_DRIVE_ATTEMPTS}. */
  static final int MAX_APPEND_ATTEMPTS = 5;

  private static final String SELECT_LAST =
      "SELECT version, message FROM nessy_transcript"
          + " WHERE conversation_id = ? ORDER BY version DESC LIMIT 1";
  private static final String INSERT_IF_NOT_EXISTS =
      "INSERT INTO nessy_transcript (conversation_id, version, message) VALUES (?, ?, ?) IF NOT EXISTS";
  private static final String SELECT_ALL =
      "SELECT version, message FROM nessy_transcript WHERE conversation_id = ? ORDER BY version ASC";
  private static final String SELECT_TAIL =
      "SELECT version, message FROM nessy_transcript"
          + " WHERE conversation_id = ? AND version > ? ORDER BY version ASC";
  private static final String SELECT_PAGE_DESCENDING =
      "SELECT version, message FROM nessy_transcript"
          + " WHERE conversation_id = ? AND version < ? ORDER BY version DESC LIMIT ?";

  private final CqlSession session;
  private final StateCodec codec;
  private final CqlExecutor executor;

  public CassandraTranscript(CqlSession session, ObjectMapper mapper) {
    this(session, mapper, session::execute);
  }

  /**
   * The public constructor's real work, plus a seam over the one place every statement in this
   * class is actually sent to the driver. {@link CqlSession} pulls in the DSE graph, reactive, and
   * continuous-paging session interfaces alongside the plain CQL ones, so hand-rolling a delegating
   * fake of the whole session is disproportionate to what the bounded-attempts test needs — one
   * seam over the single {@code execute} call, package-private for that test alone, is the smaller
   * surface.
   */
  CassandraTranscript(CqlSession session, ObjectMapper mapper, CqlExecutor executor) {
    this.session = Objects.requireNonNull(session, "session must not be null");
    this.codec = new StateCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
  }

  /**
   * Bootstraps {@code transcript-schema.cql} against {@code session}, then returns a working
   * transcript.
   */
  public static CassandraTranscript create(CqlSession session, ObjectMapper mapper) {
    CassandraTranscript transcript = new CassandraTranscript(session, mapper);
    transcript.bootstrap();
    return transcript;
  }

  private void bootstrap() {
    session.execute(readSchemaResource());
  }

  private static String readSchemaResource() {
    try (InputStream in = CassandraTranscript.class.getResourceAsStream("transcript-schema.cql")) {
      if (in == null) {
        throw new IllegalStateException(
            "transcript-schema.cql not found on the classpath next to CassandraTranscript");
      }
      String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return raw.lines()
          .filter(line -> !line.strip().startsWith("--"))
          .collect(Collectors.joining("\n"))
          .strip();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read transcript-schema.cql", e);
    }
  }

  @Override
  public Entry append(ConversationId id, Message message) {
    Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    Objects.requireNonNull(message, "message must not be null");
    Optional<Entry> last = readLast(id);
    for (int attempt = 1; ; attempt++) {
      if (last.isPresent() && last.get().message().equals(message)) {
        return last.get();
      }
      long nextVersion = last.map(entry -> entry.version() + 1).orElse(0L);
      if (insertIfNotExists(id, nextVersion, message)) {
        return new Entry(nextVersion, message);
      }
      if (attempt >= MAX_APPEND_ATTEMPTS) {
        throw new IllegalStateException(
            "gave up appending to conversation "
                + id.value()
                + " after "
                + attempt
                + " attempts: another writer keeps winning the version-"
                + nextVersion
                + " insert");
      }
      // Another writer's insert won this version — re-read (at SERIAL, so the winner's write is
      // visible) and re-evaluate the no-stutter rule against it, same as JdbcTranscript's
      // SELECT ... FOR UPDATE re-read gets serialized for free.
      last = readLast(id);
    }
  }

  @Override
  public List<Entry> all(ConversationId id) {
    Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    return queryEntries(SimpleStatement.newInstance(SELECT_ALL, id.value()));
  }

  @Override
  public List<Entry> tail(ConversationId id, long afterVersion) {
    Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    return queryEntries(SimpleStatement.newInstance(SELECT_TAIL, id.value(), afterVersion));
  }

  @Override
  public List<Entry> page(ConversationId id, long beforeVersion, int limit) {
    Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    // The newest `limit` rows below the bound, fetched newest-first so LIMIT keeps the right
    // window, then reversed back into the ascending order the contract promises.
    List<Entry> newestFirst =
        queryEntries(
            SimpleStatement.newInstance(SELECT_PAGE_DESCENDING, id.value(), beforeVersion, limit));
    return List.copyOf(newestFirst.reversed());
  }

  private Optional<Entry> readLast(ConversationId id) {
    Statement<?> statement =
        SimpleStatement.builder(SELECT_LAST)
            .addPositionalValue(id.value())
            .setConsistencyLevel(DefaultConsistencyLevel.SERIAL)
            .build();
    Row row = executor.execute(statement).one();
    return row == null ? Optional.empty() : Optional.of(toEntry(row));
  }

  private boolean insertIfNotExists(ConversationId id, long version, Message message) {
    Statement<?> statement =
        SimpleStatement.newInstance(
            INSERT_IF_NOT_EXISTS, id.value(), version, codec.writeMessage(message));
    return executor.execute(statement).wasApplied();
  }

  private List<Entry> queryEntries(Statement<?> statement) {
    ResultSet resultSet = executor.execute(statement);
    List<Entry> entries = new ArrayList<>();
    for (Row row : resultSet) {
      entries.add(toEntry(row));
    }
    return List.copyOf(entries);
  }

  private Entry toEntry(Row row) {
    return new Entry(row.getLong("version"), codec.readMessage(row.getString("message")));
  }

  /**
   * The one seam every statement in this class is sent through; {@link
   * #CassandraTranscript(CqlSession, ObjectMapper)} wires it to {@link
   * CqlSession#execute(Statement)} directly. See the package-private constructor's javadoc for why
   * this exists instead of a hand-rolled {@link CqlSession} fake.
   */
  @FunctionalInterface
  interface CqlExecutor {
    ResultSet execute(Statement<?> statement);
  }
}
