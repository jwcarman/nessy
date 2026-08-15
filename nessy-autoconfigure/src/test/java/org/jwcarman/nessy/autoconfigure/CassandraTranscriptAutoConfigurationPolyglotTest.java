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
package org.jwcarman.nessy.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.store.jdbc.JdbcConversationStore;
import org.jwcarman.nessy.store.jdbc.JdbcParks;
import org.jwcarman.nessy.transcript.cassandra.CassandraTranscript;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The polyglot pin (design §4's thesis): a {@link CqlSession} bean alongside a {@link DataSource}
 * bean yields a {@link CassandraTranscript} {@code Transcript}, the JDBC {@link Memory} composing
 * over it, and the JDBC {@link ConversationStore}/{@link Parks} still in play. {@link
 * CassandraTranscript#create} always bootstraps real schema DDL (no non-bootstrapping variant), so
 * this is the one test in the module that needs a genuinely live session — a real Testcontainers
 * {@link CassandraContainer}, mirroring {@code CassandraTranscriptTest}
 * (nessy-transcript-cassandra). Tagged {@code container} so the offline default build never needs
 * Docker; the JDBC side of the context stays offline ({@code UnusedDataSource} + {@code
 * bootstrap-schema=false}), exactly as {@link JdbcPersistenceAutoConfigurationTest} runs it.
 */
@Testcontainers
@Tag("container")
class CassandraTranscriptAutoConfigurationPolyglotTest {

  private static final String KEYSPACE = "nessy_autoconfigure_test";

  @Container
  static final CassandraContainer<?> CASSANDRA = new CassandraContainer<>("cassandra:5.0");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CassandraTranscriptAutoConfiguration.class,
                  JdbcPersistenceAutoConfiguration.class));

  @Test
  void
      a_cql_session_and_a_datasource_together_yield_a_cassandra_transcript_with_jdbc_everywhere_else() {
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
    try (CqlSession session =
        CqlSession.builder()
            .addContactPoint(contactPoint)
            .withLocalDatacenter(localDatacenter)
            .withKeyspace(KEYSPACE)
            .build()) {
      runner
          .withBean(CqlSession.class, () -> session)
          .withBean(DataSource.class, UnusedDataSource::new)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withPropertyValues("nessy.jdbc.bootstrap-schema=false")
          .run(
              context -> {
                assertThat(context).hasSingleBean(Transcript.class);
                assertThat(context.getBean(Transcript.class))
                    .isInstanceOf(CassandraTranscript.class);
                assertThat(context.getBean(ConversationStore.class))
                    .isInstanceOf(JdbcConversationStore.class);
                assertThat(context.getBean(Parks.class)).isInstanceOf(JdbcParks.class);

                Memory memory = context.getBean(Memory.class);
                ConversationId id = ConversationId.generate();
                Message message = Message.user("polyglot pin");
                memory.remember(id, message);

                Transcript transcript = context.getBean(Transcript.class);
                assertThat(transcript.all(id))
                    .extracting(Transcript.Entry::message)
                    .containsExactly(message);
              });
    }
  }

  /**
   * A {@link DataSource} that is never actually connected to — the JDBC side of this context keeps
   * bootstrap off, so construction alone must suffice. Mirrors {@code
   * JdbcPersistenceAutoConfigurationTest}'s own {@code UnusedDataSource}.
   */
  private static final class UnusedDataSource implements DataSource {

    @Override
    public Connection getConnection() {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationPolyglotTest");
    }

    @Override
    public Connection getConnection(String username, String password) {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationPolyglotTest");
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationPolyglotTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationPolyglotTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationPolyglotTest");
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
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationPolyglotTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
