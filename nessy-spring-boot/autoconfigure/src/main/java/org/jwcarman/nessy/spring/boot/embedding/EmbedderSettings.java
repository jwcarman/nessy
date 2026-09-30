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

import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What {@code nessy.embedders.<id>} says. Every field optional; a preset fills the gaps, and a
 * custom embedder (an id not in the catalogue) must supply {@code wire}, {@code baseUrl} and {@code
 * apiKey} itself.
 *
 * <p>{@code vendor} is honoured on the {@link EmbeddingWire#OPENAI} wire only: the Gemini and
 * Voyage adapters report their own fixed vendor, and the setting is ignored for them. {@code
 * properties} are vendor properties for this embedder, bound as {@code Map<String, String>} so a
 * dotted key stays one entry; a preset's defaults are overlaid by them, name by name.
 */
record EmbedderSettings(
    @Nullable EmbeddingWire wire,
    @Nullable String baseUrl,
    @Nullable String apiKey,
    @Nullable Boolean enabled,
    @Nullable String vendor,
    @Nullable Map<String, String> properties) {

  /**
   * Redacts the key, and prints property names but never their values: the generated form would
   * print both in a log or a test failure.
   */
  @Override
  public String toString() {
    return "EmbedderSettings[wire="
        + wire
        + ", baseUrl="
        + baseUrl
        + ", apiKey="
        + (apiKey != null ? "***" : "null")
        + ", enabled="
        + enabled
        + ", vendor="
        + vendor
        + ", properties="
        + (properties != null ? new TreeSet<>(properties.keySet()) : "null")
        + "]";
  }
}
