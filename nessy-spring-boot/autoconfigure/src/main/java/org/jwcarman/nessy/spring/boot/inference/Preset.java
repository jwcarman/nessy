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
import org.jspecify.annotations.Nullable;

/**
 * A provider Nessy knows how to reach, waiting for its ingredient: a key for a hosted vendor, an
 * explicit {@code enabled} for one running on this machine.
 */
record Preset(
    String id,
    Wire wire,
    @Nullable String baseUrl,
    String vendor,
    List<String> keyProperties,
    @Nullable String keylessApiKey) {

  static final List<Preset> CATALOGUE =
      List.of(
          new Preset("openai", Wire.OPENAI, null, "openai", List.of("openai.api-key"), null),
          new Preset(
              "xai", Wire.OPENAI, "https://api.x.ai/v1", "x_ai", List.of("xai.api-key"), null),
          new Preset(
              "anthropic", Wire.ANTHROPIC, null, "anthropic", List.of("anthropic.api-key"), null),
          new Preset(
              "gemini",
              Wire.GEMINI,
              null,
              "gcp.gemini",
              List.of("gemini.api-key", "google.api-key"),
              null),
          new Preset(
              "lmstudio",
              Wire.OPENAI,
              "http://localhost:1234/v1",
              "lmstudio",
              List.of(),
              "lm-studio"));

  boolean keyless() {
    return keylessApiKey != null;
  }
}
