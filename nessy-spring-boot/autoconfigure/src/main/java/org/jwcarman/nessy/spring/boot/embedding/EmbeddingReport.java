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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Says, once, at startup, what will make the vectors: every registered {@link EmbeddingProvider} by
 * its registry id, and the factory's default -- a fact that exists only once the two properties are
 * read together.
 *
 * <p>A resolved preset or custom embedder prints its wire, endpoint, vendor and property names; an
 * application's own bean prints only what it can ask the bean for. The vendor printed is always the
 * bean's own {@link EmbeddingProvider#vendor()}, since the Gemini and Voyage wires ignore a vendor
 * override. Never the key, and never a property's value -- only its name. Nothing registered is
 * INFO, not WARN: an application without embeddings is not misconfigured, and a store that wanted
 * one says so itself.
 */
final class EmbeddingReport implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingReport.class);

  private final ObjectProvider<ResolvedEmbedders> resolvedEmbedders;
  private final ListableBeanFactory beans;
  private final NessyProperties properties;

  EmbeddingReport(
      ObjectProvider<ResolvedEmbedders> resolvedEmbedders,
      ListableBeanFactory beans,
      NessyProperties properties) {
    this.resolvedEmbedders = resolvedEmbedders;
    this.beans = beans;
    this.properties = properties;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (!log.isInfoEnabled()) {
      return;
    }
    Map<String, EmbeddingProvider> providers = beans.getBeansOfType(EmbeddingProvider.class);
    if (providers.isEmpty()) {
      log.info("NESSY EMBEDDING: no embedder is configured; stores rank by recency");
      return;
    }
    Map<String, ResolvedEmbedder> byBean = resolvedByBeanName();
    Map<String, String> lines = new TreeMap<>();
    providers.forEach(
        (beanName, provider) -> {
          ResolvedEmbedder resolved = byBean.get(beanName);
          String id = resolved != null ? resolved.id() : beanName;
          lines.put(id, describe(id, resolved, provider));
        });
    log.info("NESSY EMBEDDING: embedders: {}", String.join("; ", lines.values()));
    if (!startersOwnFactory()) {
      return;
    }
    if (properties.embedder() == null) {
      log.info("NESSY EMBEDDING: no default embedder; every store names its own");
    } else {
      log.info(
          "NESSY EMBEDDING: default: {} / {}{}",
          properties.embedder(),
          properties.embeddingModel(),
          properties.embeddingDimension() == null
              ? ""
              : ", " + properties.embeddingDimension() + " wide");
    }
  }

  /**
   * The default is the starter's own factory's to hold. An application's factory has its own, which
   * these two properties do not describe, so saying "default: x / y" would be a false report.
   */
  private boolean startersOwnFactory() {
    return beans.containsBean(EmbeddingProvidersAutoConfiguration.FACTORY_BEAN)
        && beans.isTypeMatch(
            EmbeddingProvidersAutoConfiguration.FACTORY_BEAN, DefaultEmbedderFactory.class);
  }

  private Map<String, ResolvedEmbedder> resolvedByBeanName() {
    List<ResolvedEmbedder> resolved =
        resolvedEmbedders.getIfAvailable(() -> new ResolvedEmbedders(List.of())).embedders();
    return resolved.stream().collect(Collectors.toMap(ResolvedEmbedder::beanName, r -> r));
  }

  private static String describe(
      String id, @Nullable ResolvedEmbedder resolved, EmbeddingProvider provider) {
    if (resolved == null) {
      return id + " (vendor " + provider.vendor() + ")";
    }
    String endpoint = resolved.baseUrl() != null ? resolved.baseUrl() : "the vendor's own endpoint";
    return id
        + " ("
        + resolved.wire().propertyValue()
        + ", "
        + endpoint
        + ", vendor "
        + provider.vendor()
        + (resolved.properties().isEmpty()
            ? ""
            : ", properties " + new TreeSet<>(resolved.properties().keySet()))
        + ")";
  }
}
