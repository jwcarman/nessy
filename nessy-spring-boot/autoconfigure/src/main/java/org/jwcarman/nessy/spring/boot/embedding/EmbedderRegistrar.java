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
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns {@code nessy.embedders.*} into {@link EmbeddingProvider} beans, one per lit preset or
 * custom embedder, named {@code <id>Embeddings} -- the id itself is the inference registrar's bean
 * when the same key lights both -- and publishes {@link ResolvedEmbedders} so the factory registers
 * each under its id.
 *
 * <p>Not observed here: the factory wraps every embedder it mints, which is where the model and the
 * width are known. The container owns each provider's connection and closes it at shutdown.
 */
class EmbedderRegistrar
    implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware {

  private static final Logger log = LoggerFactory.getLogger(EmbedderRegistrar.class);

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
    Map<String, EmbedderSettings> settings =
        Binder.get(environment)
            .bind("nessy.embedders", Bindable.mapOf(String.class, EmbedderSettings.class))
            .orElse(Map.of());
    List<ResolvedEmbedder> resolved = EmbedderCatalogue.resolve(settings, environment::getProperty);

    List<ResolvedEmbedder> registered = new ArrayList<>();
    for (ResolvedEmbedder embedder : resolved) {
      String beanName = embedder.beanName();
      if (registry.containsBeanDefinition(beanName)) {
        throw new IllegalStateException(
            "a bean named '"
                + beanName
                + "' and the "
                + embedder.id()
                + " embedder would both be registered as '"
                + beanName
                + "'; rename the bean or unset the embedder's key");
      }
      if (!WireEmbedders.isPresent(embedder.wire(), beanFactory.getBeanClassLoader())) {
        log.info(
            "NESSY EMBEDDING: {} is configured but {} is not on the classpath; skipped",
            embedder.id(),
            WireEmbedders.artifactId(embedder.wire()));
        continue;
      }
      registry.registerBeanDefinition(beanName, providerDefinition(embedder));
      registered.add(embedder);
    }
    ResolvedEmbedders resolvedEmbedders = new ResolvedEmbedders(registered);
    registry.registerBeanDefinition(
        "nessyResolvedEmbedders",
        new RootBeanDefinition(ResolvedEmbedders.class, () -> resolvedEmbedders));
  }

  private RootBeanDefinition providerDefinition(ResolvedEmbedder embedder) {
    return new RootBeanDefinition(
        EmbeddingProvider.class,
        () -> {
          JsonMapper mapper = beanFactory.getBeanProvider(JsonMapper.class).getIfAvailable();
          return WireEmbedders.build(embedder, mapper, beanFactory.getBeanClassLoader())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "the " + embedder.id() + " embedder's adapter vanished"));
        });
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // Nothing: every bean definition this class contributes is already registered by the time
    // this runs.
  }
}
