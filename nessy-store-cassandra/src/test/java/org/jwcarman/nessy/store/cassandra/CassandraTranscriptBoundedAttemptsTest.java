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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Message;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The bounded-attempts pin (design §3, last paragraph): an appender that keeps losing the LWT race
 * gives up loudly rather than spinning forever.
 *
 * <p>{@link CqlSession}'s surface (it also extends the DSE graph, reactive, and continuous-paging
 * session interfaces) makes a hand-rolled delegating fake of the whole session disproportionate to
 * what this test needs. Instead it drives {@link CassandraTranscript} through the package-private
 * {@code CqlExecutor} seam documented on that class: reads still hit the real container so the
 * append loop sees a genuinely empty partition, but every {@code INSERT ... IF NOT EXISTS} is
 * answered with a canned not-applied result, so no writer — real or imagined — ever wins the race.
 * Requires Docker; tagged {@code container} so the offline default build never needs it.
 */
@Testcontainers
@Tag("container")
class CassandraTranscriptBoundedAttemptsTest {

  private static final String KEYSPACE = "nessy_transcript_bounded_attempts_test";
  private static final NotAppliedResultSet NOT_APPLIED = new NotAppliedResultSet();

  @Container
  static final CassandraContainer<?> CASSANDRA = new CassandraContainer<>("cassandra:5.0");

  private static CqlSession session;

  @BeforeAll
  static void a_session_with_its_own_bootstrapped_keyspace() {
    InetSocketAddress contactPoint = CASSANDRA.getContactPoint();
    String localDatacenter = CASSANDRA.getLocalDatacenter();
    try (CqlSession bootstrapSession =
        CqlSession.builder()
            .addContactPoint(contactPoint)
            .withLocalDatacenter(localDatacenter)
            .build()) {
      bootstrapSession.execute(
          "CREATE KEYSPACE IF NOT EXISTS "
              + KEYSPACE
              + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
    }
    session =
        CqlSession.builder()
            .addContactPoint(contactPoint)
            .withLocalDatacenter(localDatacenter)
            .withKeyspace(KEYSPACE)
            .build();
    CassandraTranscript.create(session, new ObjectMapper());
  }

  @AfterAll
  static void close_the_session() {
    session.close();
  }

  @Test
  void an_appender_that_keeps_losing_the_lwt_race_gives_up_naming_the_contention() {
    ConversationId id = ConversationId.generate();
    Message message = Message.user("never lands");
    CassandraTranscript transcript =
        new CassandraTranscript(session, new ObjectMapper(), this::alwaysContestInserts);

    assertThatThrownBy(() -> transcript.append(id, message))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(id.value())
        .hasMessageContaining(String.valueOf(CassandraTranscript.MAX_APPEND_ATTEMPTS));
  }

  private ResultSet alwaysContestInserts(Statement<?> statement) {
    if (isAnInsert(statement)) {
      return NOT_APPLIED;
    }
    return session.execute(statement);
  }

  private static boolean isAnInsert(Statement<?> statement) {
    return statement instanceof SimpleStatement simple
        && simple.getQuery().startsWith("INSERT INTO nessy_transcript");
  }

  /** A {@link ResultSet} that reports every write it stands in for as not applied. */
  private static final class NotAppliedResultSet implements ResultSet {

    @Override
    public ColumnDefinitions getColumnDefinitions() {
      throw new UnsupportedOperationException("not needed by the bounded-attempts test");
    }

    @Override
    public List<ExecutionInfo> getExecutionInfos() {
      return List.of();
    }

    @Override
    public boolean isFullyFetched() {
      return true;
    }

    @Override
    public int getAvailableWithoutFetching() {
      return 0;
    }

    @Override
    public boolean wasApplied() {
      return false;
    }

    @Override
    public Iterator<Row> iterator() {
      return Collections.emptyIterator();
    }
  }
}
