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

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.harness.direct.DirectHarnessFactoryConfig;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
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
// No @ConditionalOnClass here, deliberately. Both doors' factories live in nessy-engine, which
// this module depends on outright, so a condition naming either class can never be false -- and a
// reader who found one would reasonably conclude the classpath tells the two doors apart. It does
// not. An application that wants one door excludes the other by name.
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
      ObservationRegistry observations,
      ObjectProvider<DirectBackend> backends,
      ObjectProvider<PlatformTransactionManager> transactions,
      ObjectProvider<InputSchemaGenerator> schemas,
      ObjectProvider<JsonMapper> mappers,
      ObjectProvider<Customizer<DirectHarnessFactoryConfig>> customizers) {
    JsonMapper mapper = mappers.getIfAvailable(() -> JsonMapper.builder().build());
    // A backend chooses its own lock together with its own stores; honouring a stray Locks bean
    // here would reintroduce exactly the mismatch a backend exists to rule out (an in-memory lock
    // guarding JDBC stores, with no exclusion between two instances and no transaction around the
    // direct door's steps). So an application says which backend it wants as a whole, or gets the
    // JDBC one built from the DataSource and whatever PlatformTransactionManager it has.
    DirectBackend backend =
        backends.getIfAvailable(
            () ->
                new JdbcDirectBackend(
                    dataSource,
                    transactions.getIfAvailable(() -> new JdbcTransactionManager(dataSource)),
                    null));

    // The starter says what it knows, then every customizer bean has its turn. An application
    // adds a lease, a listener or a store of its own without declaring the whole factory.
    List<Customizer<DirectHarnessFactoryConfig>> all = new ArrayList<>();
    all.add(
        config ->
            config
                .backend(backend)
                .provider(models)
                .schemas(schemas.getIfAvailable(VictoolsInputSchemaGenerator::new))
                .mapper(mapper)
                .observations(observations));
    customizers.orderedStream().forEach(all::add);
    return DefaultDirectHarnessFactory.of(all);
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
