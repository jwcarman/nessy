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
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.transcript.cassandra.CassandraTranscript;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link CassandraTranscriptAutoConfiguration} against the same offline {@link
 * ApplicationContextRunner} style {@link JdbcPersistenceAutoConfigurationTest} uses. Every case
 * here — {@code CqlSession} absent, the property switch, a user-declared {@link Transcript} — never
 * calls {@link CqlSession#execute}, so an unused hand-rolled stand-in (no mocking library; see
 * {@link #unusedCqlSession()}) is enough, exactly as {@code UnusedDataSource} stands in for the
 * JDBC side. The one case that genuinely needs a live session — the polyglot pin, design §4's
 * thesis — lives in {@link CassandraTranscriptAutoConfigurationPolyglotTest} instead, so the
 * container it requires never starts for this offline suite.
 */
class CassandraTranscriptAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CassandraTranscriptAutoConfiguration.class,
                  JdbcPersistenceAutoConfiguration.class));

  @Test
  void no_cql_session_means_jdbc_takes_the_transcript_exactly_as_today() {
    runner
        .withBean(DataSource.class, UnusedDataSource::new)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("nessy.jdbc.bootstrap-schema=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(Transcript.class);
              assertThat(context.getBean(Transcript.class))
                  .isNotInstanceOf(CassandraTranscript.class);
            });
  }

  @Test
  void nessy_cassandra_enabled_false_backs_the_cassandra_transcript_off() {
    runner
        .withBean(CqlSession.class, CassandraTranscriptAutoConfigurationTest::unusedCqlSession)
        .withBean(DataSource.class, UnusedDataSource::new)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("nessy.jdbc.bootstrap-schema=false", "nessy.cassandra.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(Transcript.class);
              assertThat(context.getBean(Transcript.class))
                  .isNotInstanceOf(CassandraTranscript.class);
            });
  }

  @Test
  void a_user_declared_transcript_bean_wins_and_both_auto_configs_back_off() {
    Transcript mine = Transcript.inMemory();
    runner
        .withBean(CqlSession.class, CassandraTranscriptAutoConfigurationTest::unusedCqlSession)
        .withBean(DataSource.class, UnusedDataSource::new)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("nessy.jdbc.bootstrap-schema=false")
        .withBean("mine", Transcript.class, () -> mine)
        .run(
            context -> {
              assertThat(context.getBean(Transcript.class)).isSameAs(mine);
              assertThat(context).hasSingleBean(Memory.class);
            });
  }

  /**
   * {@code @ConditionalOnBean(CqlSession.class)} is only reliable once Boot's own Cassandra
   * auto-configuration has run, the same reliability gap {@link
   * JdbcPersistenceAutoConfigurationTest#jdbc_persistence_is_pinned_after_boot_s_datasource_autoconfiguration()}
   * pins for the JDBC side; {@code before = JdbcPersistenceAutoConfiguration.class} is design §4's
   * arbitration rule itself — the Cassandra {@code Transcript} bean must land first so the JDBC one
   * backs off by its own {@code @ConditionalOnMissingBean} rule. Neither ordering constraint is
   * reproducible by an {@link ApplicationContextRunner}'s classpath-order tie-break, so both pins
   * are asserted directly against the annotation.
   */
  @Test
  void cassandra_transcript_is_pinned_after_boot_s_cassandra_autoconfiguration_and_before_jdbc() {
    AutoConfiguration autoConfiguration =
        CassandraTranscriptAutoConfiguration.class.getAnnotation(AutoConfiguration.class);
    assertThat(autoConfiguration).isNotNull();
    assertThat(autoConfiguration.after()).contains(CassandraAutoConfiguration.class);
    assertThat(autoConfiguration.before()).contains(JdbcPersistenceAutoConfiguration.class);
  }

  /**
   * A {@link CqlSession} that is never actually connected to or queried — every test that needs one
   * only proves that a bean of the right type is present, not that it can serve traffic. Mirrors
   * {@link UnusedDataSource} below, but as a {@link Proxy} rather than a hand-implemented class:
   * {@link CqlSession} pulls in the DSE graph, reactive, and continuous-paging session interfaces
   * alongside the plain CQL ones (see {@code CassandraTranscript}'s own javadoc), so a one-method
   * invocation handler is the smaller surface. Not a mocking library — {@link Proxy} is the JDK's
   * own dynamic-proxy facility.
   */
  private static CqlSession unusedCqlSession() {
    return (CqlSession)
        Proxy.newProxyInstance(
            CqlSession.class.getClassLoader(),
            new Class<?>[] {CqlSession.class},
            (proxy, method, args) -> {
              throw new UnsupportedOperationException(
                  "not used by CassandraTranscriptAutoConfigurationTest");
            });
  }

  /**
   * A {@link DataSource} that is never actually connected to — every test here keeps JDBC bootstrap
   * off, so construction alone must suffice. Mirrors {@code JdbcPersistenceAutoConfigurationTest}'s
   * own {@code UnusedDataSource}.
   */
  private static final class UnusedDataSource implements DataSource {

    @Override
    public Connection getConnection() {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationTest");
    }

    @Override
    public Connection getConnection(String username, String password) {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationTest");
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException(
          "not used by CassandraTranscriptAutoConfigurationTest");
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
          "not used by CassandraTranscriptAutoConfigurationTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
