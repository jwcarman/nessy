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
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.engine.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.direct.InMemoryLocks;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.engine.store.JdbcAgentEventStore;
import org.jwcarman.nessy.engine.store.JdbcPayloadStore;
import org.jwcarman.nessy.spi.lock.Locks;
import org.jwcarman.nessy.spi.store.PayloadStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The direct door, for an application that wants an answer back.
 *
 * <p>Its own auto-configuration rather than a bean on the queued one, because the two doors are
 * peers and an application that wants one should not have to describe the other. Nothing here reads
 * {@code nessy.system-prompt} or wants an agent type: what an agent is for is said when a harness
 * is created, not in properties.
 *
 * <p><b>Durable when there is a database, and not otherwise.</b> A {@code DataSource} means the
 * events and the content are written down, so a conversation outlives the process that started it.
 * Without one this does not appear at all, and an application that wants a harness holding
 * everything in memory builds it with {@code DefaultDirectHarnessFactory.inMemory}, which is one
 * line and says what it does.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
// Bound here as well as by the queued door, because this door must stand on its own: an
// application that wants only this one excludes the other, and everything the other brought --
// the properties among them -- goes with it.
@EnableConfigurationProperties(NessyProperties.class)
@ConditionalOnClass(DefaultDirectHarnessFactory.class)
public class DirectHarnessAutoConfiguration {

  @Bean
  @ConditionalOnBean(DataSource.class)
  // Against the INTERFACE, not this method's return type. An application declaring its own
  // factory declares it as DirectHarnessFactory -- every example does -- and a condition naming
  // the concrete class does not see it, so the starter builds a second one and then demands
  // everything it needs from an application that had already said it wanted none of this.
  @ConditionalOnMissingBean(DirectHarnessFactory.class)
  public DefaultDirectHarnessFactory nessyDirectHarnessFactory(
      DataSource dataSource,
      org.jwcarman.nessy.inference.InferenceProvider models,
      NessyAutoConfiguration.NessySchema schema,
      ObjectProvider<Locks> locks,
      ObjectProvider<InputSchemaGenerator> schemas,
      ObjectProvider<JsonMapper> mappers) {
    JsonMapper mapper = mappers.getIfAvailable(() -> JsonMapper.builder().build());
    CodecFactory codecs = new JacksonCodecFactory(mapper);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    AgentEventStore events = new JdbcAgentEventStore(jdbc, codecs);
    PayloadStore payloads = new JdbcPayloadStore(jdbc, codecs);
    return new DefaultDirectHarnessFactory(
        // In memory unless an application said otherwise. One process is the common case for this
        // door, and a lease across machines is something an application opts into by declaring one.
        locks.getIfAvailable(InMemoryLocks::new),
        events,
        payloads,
        models,
        schemas.getIfAvailable(VictoolsInputSchemaGenerator::new),
        mapper);
  }

  /**
   * Every {@link NarrationListener} bean, attached to every harness this factory makes.
   *
   * <p>The same mechanism the queued door has, deliberately: a listener an application declares
   * should reach its agents whichever door they are behind, and the difference is not something
   * anybody could predict from the outside. Attached after every bean exists rather than at the
   * factory's making, so a listener that reads the story is not a circle.
   */
  @Bean
  @ConditionalOnBean(DefaultDirectHarnessFactory.class)
  public SmartInitializingSingleton nessyDirectListeners(
      DefaultDirectHarnessFactory factory, ObjectProvider<NarrationListener> listeners) {
    return () -> listeners.orderedStream().forEach(factory::listener);
  }

  /** Says what will actually answer, before a single turn runs. */
  @Bean
  @ConditionalOnMissingBean
  public InferenceReport nessyInferenceReport(
      ObjectProvider<org.jwcarman.nessy.inference.InferenceProvider> providers,
      NessyProperties properties,
      org.springframework.core.env.Environment environment) {
    return new InferenceReport(providers, properties, environment);
  }
}
