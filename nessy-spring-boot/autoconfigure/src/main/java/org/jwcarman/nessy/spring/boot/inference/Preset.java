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
import java.util.Map;
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
    @Nullable String keylessApiKey,
    Map<String, String> defaultProperties) {

  /** No default properties: every preset whose strict row has not been measured. */
  Preset(
      String id,
      Wire wire,
      @Nullable String baseUrl,
      String vendor,
      List<String> keyProperties,
      @Nullable String keylessApiKey) {
    this(id, wire, baseUrl, vendor, keyProperties, keylessApiKey, Map.of());
  }

  static final List<Preset> CATALOGUE =
      List.of(
          new Preset(
              "openai",
              Wire.OPENAI_CHAT,
              null,
              "openai",
              List.of("openai.api-key"),
              null,
              // Measured 2026-09-30 against OpenAI on the chat wire (OpenAiChatLiveTest): strict
              // tools with an Optional component and with a record holding a sealed field are
              // accepted and called. Every other row waits for its own measurement (spec
              // section 10).
              Map.of("openai.tools.strict", "true")),
          new Preset(
              "xai", Wire.OPENAI_CHAT, "https://api.x.ai/v1", "x_ai", List.of("xai.api-key"), null),
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
              "openrouter",
              Wire.OPENAI_CHAT,
              "https://openrouter.ai/api/v1",
              "openrouter",
              List.of("openrouter.api-key"),
              null),
          new Preset(
              "nvidia",
              Wire.OPENAI_CHAT,
              "https://integrate.api.nvidia.com/v1",
              "nvidia",
              List.of("nvidia.api-key"),
              null),
          new Preset(
              "groq",
              Wire.OPENAI_CHAT,
              "https://api.groq.com/openai/v1",
              "groq",
              List.of("groq.api-key"),
              null),
          new Preset(
              "mistral",
              Wire.OPENAI_CHAT,
              "https://api.mistral.ai/v1",
              "mistral_ai",
              List.of("mistral.api-key"),
              null),
          new Preset(
              "lmstudio",
              Wire.OPENAI_CHAT,
              "http://localhost:1234/v1",
              "lmstudio",
              List.of(),
              "lm-studio"),
          new Preset(
              "ollama",
              Wire.OPENAI_CHAT,
              "http://localhost:11434/v1",
              "ollama",
              List.of(),
              "ollama"));

  boolean keyless() {
    return keylessApiKey != null;
  }
}
