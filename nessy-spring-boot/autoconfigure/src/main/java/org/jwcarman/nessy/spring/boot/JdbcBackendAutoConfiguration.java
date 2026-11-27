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
package org.jwcarman.nessy.spring.boot;

import javax.sql.DataSource;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend;
import org.jwcarman.nessy.backend.jdbc.JdbcLeases;
import org.jwcarman.nessy.backend.jdbc.JdbcQueuedBackend;
import org.jwcarman.nessy.backend.lease.Leases;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The JDBC backend, when there is something to build it from.
 *
 * <p>{@code nessy-backend-jdbc} is an optional module -- unlike the engine's own doors, which used
 * to live beside it and could never see it absent, a starter can be on an application's classpath
 * without the JDBC backend, so {@link ConditionalOnClass} is a real fork here rather than a
 * condition that can never be false. Also conditional on a {@link DataSource} bean: without one,
 * there is nothing to build a JDBC backend over, and the in-memory backend takes the direct door
 * instead.
 *
 * <p>{@code PlatformTransactionManager} is taken as an ordinary parameter rather than through an
 * {@code ObjectProvider} with a fallback: Boot's own {@code
 * DataSourceTransactionManagerAutoConfiguration} auto-configures one whenever a single {@code
 * DataSource} is on the classpath and none of the application's own is declared, which is exactly
 * the condition this class already requires.
 *
 * <p><b>Self-sufficient on purpose.</b> The tables and the codec factory both belong here rather
 * than in {@link NessyAutoConfiguration}: an application that excludes that class, or that runs
 * with no {@code DataSource} at all, must not orphan this backend.
 */
@AutoConfiguration(
    after = DataSourceAutoConfiguration.class,
    afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
    // Named rather than imported: Substrate is optional, and an application without it never
    // pulls this class in. Ordered ahead so the CodecFactory bean below exists by the time
    // Substrate's own journal factory asks its @ConditionalOnBean(CodecFactory.class) whether one
    // is there.
    beforeName = "org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration")
@ConditionalOnClass(JdbcDirectBackend.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(NessyProperties.class)
public class JdbcBackendAutoConfiguration {

  /**
   * Named by every store this class hands out, and used by none of them: asking for it is what
   * makes Boot run the database initializers -- migratory, which runs every module's changelog --
   * before anything exists that would query the tables.
   *
   * <p>It belongs here rather than on a door. A door takes a backend and cannot see what is behind
   * it, so a door that asked for a schema would be a door that knew what a table is; and the
   * in-memory backend, which satisfies the same interface, has nothing to create. Nessy runs no SQL
   * of its own: an application that manages the tables itself sets {@code migratory.enabled=false}
   * and creates them however it sees fit.
   */
  @Bean
  @ConditionalOnMissingBean
  @DependsOnDatabaseInitialization
  public NessySchema nessySchema() {
    return new NessySchema();
  }

  /** A marker, so every bean that needs tables can depend on the tables existing. */
  public record NessySchema() {}

  @Bean
  @ConditionalOnMissingBean
  public DirectBackend directBackend(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      CodecFactory codecs,
      NessySchema schema) {
    if (codecs instanceof StorageLayers(CodecFactory values, Codec<byte[]> transform)) {
      return new JdbcDirectBackend(dataSource, transactions, values, transform);
    }
    return new JdbcDirectBackend(dataSource, transactions, codecs);
  }

  @Bean
  @ConditionalOnMissingBean
  public QueuedBackend queuedBackend(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      CodecFactory codecs,
      NessySchema schema) {
    if (codecs instanceof StorageLayers(CodecFactory values, Codec<byte[]> transform)) {
      return new JdbcQueuedBackend(dataSource, transactions, values, transform);
    }
    return new JdbcQueuedBackend(dataSource, transactions, codecs);
  }

  /**
   * Whose turn it is to do a piece of opportunistic work -- a summariser's, typically.
   *
   * <p>Here rather than with the doors because a lease IS storage, and this is where a backend's
   * storage is published. It is deliberately NOT on {@link DirectBackend} or {@link QueuedBackend}:
   * neither door takes a lease, only the summarisers do, and putting one there would make every
   * backend implementer implement leasing for a feature their application may never use.
   */
  @Bean
  @ConditionalOnMissingBean
  public Leases leases(DataSource dataSource, NessySchema schema) {
    return new JdbcLeases(dataSource);
  }
}
