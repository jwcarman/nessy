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
package org.jwcarman.nessy.model.openai;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Grok as a first-class discovery citizen with zero new provider code: OpenAI's wire protocol,
 * xAI's URL. Lives in this module because it <em>is</em> this module's provider at a fixed base URL
 * — the second of two registrations in one services file, which is the honest shape for a vendor
 * that has no module of its own and never will.
 */
public final class XaiModelProviderBootstrap implements ModelProviderBootstrap {

  static final String API_KEY_ENV_VAR = "XAI_API_KEY";
  static final String BASE_URL = "https://api.x.ai/v1";

  /**
   * xAI ships no small/cheap alias; {@code grok-4.6} is the vendor's own current general-purpose
   * recommendation (docs.x.ai, 2026-08-15).
   */
  static final String DEFAULT_MODEL_ID = "grok-4.6";

  @Override
  public String name() {
    return "xai";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(API_KEY_ENV_VAR);
  }

  @Override
  public String defaultModelId() {
    return DEFAULT_MODEL_ID;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    var apiKey = env.get(API_KEY_ENV_VAR);
    if (apiKey == null) {
      return Optional.empty();
    }
    return Optional.of(OpenAiModelProvider.create(c -> c.apiKey(apiKey).baseUrl(BASE_URL)));
  }
}
