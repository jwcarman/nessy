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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Says what will actually answer, once, at startup: every registered {@link InferenceProvider}
 * bean, by name -- there is no longer a single winner to name instead.
 *
 * <p>A resolved preset or custom provider (from {@link ResolvedProviders}) prints its wire and
 * endpoint too; an application's own bean, which the registrar never resolved, prints only what it
 * can ask the bean itself for. Either way the vendor printed is the registered bean's own {@link
 * InferenceProvider#vendor()} -- {@code resolved.vendor()} can disagree with it for the {@code
 * anthropic} and {@code gemini} wires, which ignore a vendor override, so the bean is the one
 * asked. Never the key, in either case, and never a property's value -- only its name.
 *
 * <p>It also says when {@code nessy.provider}, the default every agent type falls back to, names a
 * provider that is not registered -- most often a preset whose key is unset -- because nothing else
 * says so before the first turn fails.
 */
final class InferenceReport implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(InferenceReport.class);

  private final ObjectProvider<ResolvedProviders> resolvedProviders;
  private final ListableBeanFactory beans;
  private final @Nullable String defaultProvider;

  InferenceReport(
      ObjectProvider<ResolvedProviders> resolvedProviders,
      ListableBeanFactory beans,
      @Nullable String defaultProvider) {
    this.resolvedProviders = resolvedProviders;
    this.beans = beans;
    this.defaultProvider = defaultProvider;
  }

  @Override
  public void afterSingletonsInstantiated() {
    Map<String, InferenceProvider> providers =
        new TreeMap<>(beans.getBeansOfType(InferenceProvider.class));
    warnWhenTheDefaultIsMissing(providers);
    if (providers.isEmpty()) {
      // Not this class's business to fail: whatever needs a provider will say so far more
      // usefully, naming the agent type that wanted one.
      log.warn("NESSY INFERENCE: no provider is configured");
      return;
    }
    if (log.isInfoEnabled()) {
      Map<String, ResolvedProvider> resolved = resolvedById();
      String line =
          providers.keySet().stream()
              .map(id -> describe(id, resolved.get(id), providers.get(id)))
              .collect(Collectors.joining("; "));
      log.info("NESSY INFERENCE: providers: {}", line);
    }
  }

  private void warnWhenTheDefaultIsMissing(Map<String, InferenceProvider> providers) {
    if (defaultProvider == null
        || defaultProvider.isBlank()
        || providers.containsKey(defaultProvider)) {
      return;
    }
    log.warn(
        "NESSY INFERENCE: nessy.provider is {}, but no provider named {} is registered; turns that"
            + " use the default will fail until {}",
        defaultProvider,
        defaultProvider,
        remedy(defaultProvider));
  }

  /** What would register the provider: a preset's key or switch, or a bean of that name. */
  private static String remedy(String id) {
    Optional<Preset> preset = Preset.CATALOGUE.stream().filter(p -> p.id().equals(id)).findFirst();
    if (preset.isEmpty()) {
      return "nessy.providers." + id + " or an InferenceProvider bean named " + id + " exists";
    }
    if (preset.get().keyless()) {
      return "nessy.providers." + id + ".enabled=true is set";
    }
    String keys =
        preset.get().keyProperties().stream()
            .map(name -> name + " (" + environmentVariable(name) + ")")
            .collect(Collectors.joining(" or "));
    return keys + " is set";
  }

  /** The environment variable Spring's relaxed binding reads a property from. */
  private static String environmentVariable(String property) {
    return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
  }

  private Map<String, ResolvedProvider> resolvedById() {
    List<ResolvedProvider> resolved =
        resolvedProviders.getIfAvailable(() -> new ResolvedProviders(List.of())).providers();
    return resolved.stream().collect(Collectors.toMap(ResolvedProvider::id, r -> r));
  }

  private static String describe(String id, ResolvedProvider resolved, InferenceProvider provider) {
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
