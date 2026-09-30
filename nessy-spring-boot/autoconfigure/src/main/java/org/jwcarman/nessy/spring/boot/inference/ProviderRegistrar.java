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
package org.jwcarman.nessy.spring.boot.inference;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns {@code nessy.providers.*} into {@link InferenceProvider} beans, one per lit preset or
 * custom provider, named by its id -- so an application's own {@link InferenceProvider} bean joins
 * them under its own bean name rather than competing for a single slot.
 */
class ProviderRegistrar
    implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware {

  private static final Logger log = LoggerFactory.getLogger(ProviderRegistrar.class);

  private Environment environment;
  private ConfigurableListableBeanFactory beanFactory;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) {
    this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
  }

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    BindResult<Map<String, ProviderSettings>> bound =
        Binder.get(environment)
            .bind("nessy.providers", Bindable.mapOf(String.class, ProviderSettings.class));
    Map<String, ProviderSettings> settings = bound.orElse(Map.of());
    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(settings, environment::getProperty);

    List<ResolvedProvider> registered = new ArrayList<>();
    for (ResolvedProvider provider : resolved) {
      String id = provider.id();
      if (registry.containsBeanDefinition(id)) {
        throw new IllegalStateException(
            "a bean named '"
                + id
                + "' and the "
                + id
                + " provider would both be registered as '"
                + id
                + "'; rename the bean or unset the provider's key");
      }
      if (!WireProviders.isPresent(provider.wire(), beanFactory.getBeanClassLoader())) {
        if (log.isInfoEnabled()) {
          log.info(
              "NESSY INFERENCE: {} is configured but {} is not on the classpath; skipped",
              id,
              WireProviders.artifactId(provider.wire()));
        }
        continue;
      }
      registry.registerBeanDefinition(id, providerDefinition(provider));
      registered.add(provider);
    }
    registry.registerBeanDefinition(
        "nessyResolvedProviders", resolvedProvidersDefinition(registered));
  }

  private RootBeanDefinition providerDefinition(ResolvedProvider provider) {
    return new RootBeanDefinition(
        InferenceProvider.class,
        () -> {
          JsonMapper mapper = beanFactory.getBeanProvider(JsonMapper.class).getIfAvailable();
          InferenceProvider built =
              WireProviders.build(provider, mapper, beanFactory.getBeanClassLoader())
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "the " + provider.id() + " provider's adapter vanished"));
          ObservationRegistry observations =
              beanFactory
                  .getBeanProvider(ObservationRegistry.class)
                  .getIfAvailable(() -> ObservationRegistry.NOOP);
          return ObservedInferenceProvider.wrap(built, observations);
        });
  }

  private static RootBeanDefinition resolvedProvidersDefinition(List<ResolvedProvider> registered) {
    ResolvedProviders resolvedProviders = new ResolvedProviders(List.copyOf(registered));
    return new RootBeanDefinition(ResolvedProviders.class, () -> resolvedProviders);
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // Nothing: every bean definition this class contributes is already registered by the time
    // this runs.
  }
}
