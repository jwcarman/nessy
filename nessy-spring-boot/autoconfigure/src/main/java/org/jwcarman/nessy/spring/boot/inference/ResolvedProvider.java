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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A preset or a custom provider once every field has been decided: what {@link ProviderCatalogue}
 * resolves, {@link WireProviders} builds from, and {@link InferenceReport} reads to say what is
 * registered.
 */
record ResolvedProvider(
    String id,
    Wire wire,
    @Nullable String baseUrl,
    String vendor,
    @Nullable String apiKey,
    Map<String, String> properties) {

  ResolvedProvider {
    properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /** No properties. */
  ResolvedProvider(
      String id, Wire wire, @Nullable String baseUrl, String vendor, @Nullable String apiKey) {
    this(id, wire, baseUrl, vendor, apiKey, Map.of());
  }

  /** Redacts the key; prints property names, never values (spec section 6c). */
  @Override
  public String toString() {
    return "ResolvedProvider[id="
        + id
        + ", wire="
        + wire
        + ", baseUrl="
        + baseUrl
        + ", vendor="
        + vendor
        + ", apiKey="
        + (apiKey != null ? "***" : "null")
        + ", properties="
        + new TreeSet<>(properties.keySet())
        + "]";
  }
}
