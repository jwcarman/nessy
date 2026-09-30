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
import org.jspecify.annotations.Nullable;

/**
 * An embedder Nessy knows how to reach, waiting for its ingredient: a vendor's key. Becomes an
 * embedding provider when the key is supplied and the vendor's embedding adapter is on the
 * classpath.
 *
 * <p>Only measured rows ship: a preset is a promise that the row works. No keyless row ships -- a
 * local server is a custom embedder, stating its URL, key and model in the open -- but {@link
 * #keyless()} and the catalogue's keyless branch stay, so a measured row can join without a code
 * shape changing.
 */
record EmbedderPreset(
    String id,
    EmbeddingWire wire,
    @Nullable String baseUrl,
    String vendor,
    List<String> keyProperties,
    @Nullable String keylessApiKey,
    Map<String, String> defaultProperties) {

  static final List<EmbedderPreset> CATALOGUE =
      List.of(
          new EmbedderPreset(
              "openai",
              EmbeddingWire.OPENAI,
              null,
              "openai",
              List.of("openai.api-key"),
              null,
              Map.of()),
          new EmbedderPreset(
              "gemini",
              EmbeddingWire.GEMINI,
              null,
              "gcp.gemini",
              List.of("gemini.api-key", "google.api-key"),
              null,
              Map.of()),
          new EmbedderPreset(
              "voyage",
              EmbeddingWire.VOYAGE,
              "https://api.voyageai.com/v1",
              "voyage",
              List.of("voyage.api-key"),
              null,
              Map.of()));

  boolean keyless() {
    return keylessApiKey != null;
  }
}
