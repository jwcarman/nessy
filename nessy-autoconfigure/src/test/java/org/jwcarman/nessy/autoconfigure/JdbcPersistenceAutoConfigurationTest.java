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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.store.jdbc.JdbcPersistence;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link JdbcPersistenceAutoConfiguration} against an offline {@link DataSource} stub — every test
 * here runs with {@code bootstrap-schema=false} (or never registers a {@link DataSource} bean at
 * all) so construction never opens a connection, the same offline pattern {@code
 * JdbcPersistenceRecordTest} (nessy-store-jdbc) uses. Real-DDL bootstrap proof is chat-web's smoke
 * test's job, not this context runner's.
 */
class JdbcPersistenceAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JdbcPersistenceAutoConfiguration.class));

  @Test
  void jdbc_on_the_classpath_with_a_datasource_yields_store_and_memory() {
    runner
        .withBean(DataSource.class, UnusedDataSource::new)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("nessy.jdbc.bootstrap-schema=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ConversationStore.class);
              assertThat(context).hasSingleBean(Memory.class);
            });
  }

  @Test
  void no_datasource_means_no_jdbc_beans() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ConversationStore.class);
          assertThat(context).doesNotHaveBean(Memory.class);
        });
  }

  @Test
  void nessy_jdbc_enabled_false_is_the_master_switch() {
    runner
        .withBean(DataSource.class, UnusedDataSource::new)
        .withPropertyValues("nessy.jdbc.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ConversationStore.class);
              assertThat(context).doesNotHaveBean(Memory.class);
            });
  }

  @Test
  void a_user_declared_store_bean_wins_and_memory_still_autoconfigures() {
    ConversationStore mine = ConversationStore.inMemory();
    runner
        .withBean(DataSource.class, UnusedDataSource::new)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("nessy.jdbc.bootstrap-schema=false")
        .withBean("mine", ConversationStore.class, () -> mine)
        .run(
            context -> {
              assertThat(context.getBean(ConversationStore.class)).isSameAs(mine);
              assertThat(context).hasSingleBean(Memory.class);
            });
  }

  @Test
  void jdbc_module_absent_means_no_jdbc_beans() {
    runner
        .withClassLoader(new FilteredClassLoader(JdbcPersistence.class))
        .withBean(DataSource.class, UnusedDataSource::new)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ConversationStore.class);
              assertThat(context).doesNotHaveBean(Memory.class);
            });
  }

  /**
   * A {@link DataSource} that is never actually connected to — every test here keeps bootstrap off
   * or the beans unresolved, so construction alone must suffice. Mirrors {@code
   * JdbcPersistenceRecordTest}'s {@code UnusedDataSource} (nessy-store-jdbc).
   */
  private static final class UnusedDataSource implements DataSource {

    @Override
    public Connection getConnection() {
      throw new UnsupportedOperationException("not used by JdbcPersistenceAutoConfigurationTest");
    }

    @Override
    public Connection getConnection(String username, String password) {
      throw new UnsupportedOperationException("not used by JdbcPersistenceAutoConfigurationTest");
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException("not used by JdbcPersistenceAutoConfigurationTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by JdbcPersistenceAutoConfigurationTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by JdbcPersistenceAutoConfigurationTest");
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
      throw new UnsupportedOperationException("not used by JdbcPersistenceAutoConfigurationTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
