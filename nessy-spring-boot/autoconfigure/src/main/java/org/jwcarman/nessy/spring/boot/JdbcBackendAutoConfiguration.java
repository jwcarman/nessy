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
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend;
import org.jwcarman.nessy.backend.jdbc.JdbcLeases;
import org.jwcarman.nessy.backend.jdbc.JdbcQueuedBackend;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.jwcarman.nessy.backend.lease.Leases;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
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
 * with no {@code DataSource} at all, must not orphan this backend. {@link NessyProperties} is
 * declared again here for the same reason -- {@code nessy.initialize-schema} has to be readable
 * whether or not {@link NessyAutoConfiguration} is on the classpath.
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
   * The schema, created where the application says so.
   *
   * <p>Opt-in on purpose: {@code nessy.initialize-schema} defaults to true because an application
   * that added the starter wants the tables, but an application that manages its own migrations
   * turns it off and nothing runs a DDL file behind its back.
   */
  @Bean
  @ConditionalOnMissingBean(name = "nessySchema")
  /**
   * Named by every store this class hands out, and used by none of them: asking for it is what
   * makes Boot create the tables before anything exists that would query them.
   *
   * <p>It belongs here rather than on a door. A door takes a backend and cannot see what is behind
   * it, so a door that asked for a schema would be a door that knew what a table is; and the
   * in-memory backend, which satisfies the same interface, has nothing to create.
   */
  public NessySchema nessySchema(DataSource dataSource, NessyProperties properties) {
    boolean initialize = Boolean.TRUE.equals(properties.initializeSchema());
    if (initialize) {
      Schemas.initialize(dataSource);
    }
    return new NessySchema(initialize);
  }

  /**
   * A marker, so every bean that needs tables can depend on the tables existing.
   *
   * @param initialized whether this starter ran the DDL, or left the tables to the application
   */
  public record NessySchema(boolean initialized) {}

  @Bean
  @ConditionalOnMissingBean
  public DirectBackend directBackend(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      CodecFactory codecs,
      NessySchema schema) {
    if (codecs instanceof StorageLayers ours) {
      return new JdbcDirectBackend(dataSource, transactions, ours.values(), ours.transform());
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
    if (codecs instanceof StorageLayers ours) {
      return new JdbcQueuedBackend(dataSource, transactions, ours.values(), ours.transform());
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
