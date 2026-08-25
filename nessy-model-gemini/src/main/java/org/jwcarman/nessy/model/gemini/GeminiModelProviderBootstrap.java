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
package org.jwcarman.nessy.model.gemini;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Gemini's registration for discovery: {@code GEMINI_API_KEY} first, then {@code GOOGLE_API_KEY} —
 * Google's own documented pair, in that order, mirroring {@link GeminiProviderConfig#fromEnv()}.
 */
public final class GeminiModelProviderBootstrap implements ModelProviderBootstrap {

  static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
  static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";

  /** per ai.google.dev, 2026-08-16; model availability churns — override with NESSY_MODEL. */
  static final String DEFAULT_MODEL_ID = "gemini-3.6-flash";

  public GeminiModelProviderBootstrap() {}

  @Override
  public String name() {
    return "gemini";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(GEMINI_API_KEY_ENV_VAR, GOOGLE_API_KEY_ENV_VAR);
  }

  @Override
  public String defaultModelId() {
    return DEFAULT_MODEL_ID;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    var gemini = env.get(GEMINI_API_KEY_ENV_VAR);
    var apiKey = gemini != null ? gemini : env.get(GOOGLE_API_KEY_ENV_VAR);
    if (apiKey == null) {
      return Optional.empty();
    }
    return Optional.of(GeminiModelProvider.create(c -> c.apiKey(apiKey)));
  }
}
