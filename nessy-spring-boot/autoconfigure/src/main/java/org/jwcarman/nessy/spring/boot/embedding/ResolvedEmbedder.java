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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A preset or a custom embedder once every field has been decided: what {@link EmbedderCatalogue}
 * resolves, {@link WireEmbedders} builds from, and the report reads to say what is registered.
 *
 * <p>Its registry id is {@link #id()}; its Spring bean is {@link #beanName()}, because one bean
 * namespace also holds the inference provider the same key lights under the same id.
 */
record ResolvedEmbedder(
    String id,
    EmbeddingWire wire,
    @Nullable String baseUrl,
    String vendor,
    @Nullable String apiKey,
    Map<String, String> properties) {

  ResolvedEmbedder {
    properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /**
   * The {@code EmbeddingProvider} bean this embedder is registered as: {@code openaiEmbeddings}.
   */
  String beanName() {
    return id + "Embeddings";
  }

  /** Redacts the key; prints property names, never values. */
  @Override
  public String toString() {
    return "ResolvedEmbedder[id="
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
