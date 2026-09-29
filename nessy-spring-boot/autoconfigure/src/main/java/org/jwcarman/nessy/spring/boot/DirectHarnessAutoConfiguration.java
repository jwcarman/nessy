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
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.harness.direct.DirectHarnessFactoryConfig;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * The direct door, for an application that wants an answer back.
 *
 * <p>Its own auto-configuration rather than a bean on the queued one, because the two doors are
 * peers and an application that wants one should not have to describe the other. Nothing here reads
 * {@code nessy.system-prompt} or wants an agent type: what an agent is for is said when a harness
 * is created, not in properties.
 *
 * <p><b>Conditional on a {@link DirectBackend} bean, not on a database.</b> This door does not care
 * whether that backend is durable: {@link JdbcBackendAutoConfiguration} contributes one when there
 * is a {@code DataSource}, {@link InMemoryBackendAutoConfiguration} contributes one when there is
 * not, and this fires once either has -- ordered after both so the choice is already made by the
 * time this asks. An application that wants neither excludes both backend auto-configurations, and
 * this simply does not appear.
 */
@AutoConfiguration(
    after = {JdbcBackendAutoConfiguration.class, InMemoryBackendAutoConfiguration.class})
@ConditionalOnBean(DirectBackend.class)
// Bound here as well as by the queued door, because this door must stand on its own: an
// application that wants only this one excludes the other, and everything the other brought --
// the properties among them -- goes with it.
@EnableConfigurationProperties(NessyProperties.class)
public class DirectHarnessAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public JsonSchemaGenerator nessyJsonSchemaGenerator() {
    return new VictoolsJsonSchemaGenerator();
  }

  @Bean
  // Against the INTERFACE, not this method's return type. An application declaring its own
  // factory declares it as DirectHarnessFactory -- every example does -- and a condition naming
  // the concrete class does not see it, so the starter builds a second one and then demands
  // everything it needs from an application that had already said it wanted none of this.
  @ConditionalOnMissingBean(DirectHarnessFactory.class)
  public DefaultDirectHarnessFactory nessyDirectHarnessFactory(
      DirectBackend backend,
      ListableBeanFactory beans,
      Environment environment,
      NessyProperties properties,
      ObservationRegistry observations,
      JsonSchemaGenerator schemas,
      ObjectMapper mapper,
      ObjectProvider<Customizer<DirectHarnessFactoryConfig>> customizers) {
    // The starter says what it knows, then every customizer bean has its turn. An application
    // adds a lease, a listener or a store of its own without declaring the whole factory.
    List<Customizer<DirectHarnessFactoryConfig>> all = new ArrayList<>();
    all.add(
        config -> {
          config.backend(backend).schemas(schemas).mapper(mapper).observations(observations);
          beans
              .getBeansOfType(InferenceProvider.class)
              .forEach((name, provider) -> config.provider(ProviderId.of(name), provider));
          String provider = environment.getProperty("nessy.provider");
          String model = properties.model();
          if (provider != null && !provider.isBlank() && model != null && !model.isBlank()) {
            config.inference(
                ProviderId.of(provider), new InferenceOptions(model, properties.maxTokens()));
          }
        });
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
}
