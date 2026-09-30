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

import org.jspecify.annotations.Nullable;

/**
 * What {@code nessy.providers.<id>} says. Every field optional; a preset fills the gaps, and a
 * custom provider (an id not in the catalogue) must supply {@code wire} and {@code baseUrl} itself.
 *
 * <p>{@code vendor} is ignored for the {@link Wire#ANTHROPIC} and {@link Wire#GEMINI} wires:
 * Anthropic and Gemini report their own fixed vendor, and only the two OpenAI wires (shared by more
 * than one vendor) need an override.
 */
record ProviderSettings(
    @Nullable Wire wire,
    @Nullable String baseUrl,
    @Nullable String apiKey,
    @Nullable Boolean enabled,
    @Nullable String vendor) {

  /** Redacts the key: the generated form would otherwise print it in a log or a test failure. */
  @Override
  public String toString() {
    return "ProviderSettings[wire="
        + wire
        + ", baseUrl="
        + baseUrl
        + ", apiKey="
        + (apiKey != null ? "***" : "null")
        + ", enabled="
        + enabled
        + ", vendor="
        + vendor
        + "]";
  }
}
