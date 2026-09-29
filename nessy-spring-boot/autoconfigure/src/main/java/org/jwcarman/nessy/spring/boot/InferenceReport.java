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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.spring.boot.inference.ResolvedProvider;
import org.jwcarman.nessy.spring.boot.inference.ResolvedProviders;
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
 * endpoint; an application's own bean, which the registrar never resolved, prints only what it can
 * ask the bean itself for -- its vendor. Never the key, in either case.
 */
final class InferenceReport implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(InferenceReport.class);

  private final ObjectProvider<ResolvedProviders> resolvedProviders;
  private final ListableBeanFactory beans;

  InferenceReport(ObjectProvider<ResolvedProviders> resolvedProviders, ListableBeanFactory beans) {
    this.resolvedProviders = resolvedProviders;
    this.beans = beans;
  }

  @Override
  public void afterSingletonsInstantiated() {
    Map<String, InferenceProvider> providers =
        new TreeMap<>(beans.getBeansOfType(InferenceProvider.class));
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
        + resolved.wireValue()
        + ", "
        + endpoint
        + ", vendor "
        + resolved.vendor()
        + ")";
  }
}
