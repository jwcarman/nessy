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
 * OpenAI's registration for discovery: {@code OPENAI_API_KEY} present builds an {@link
 * OpenAiModelProvider} from that key, with {@code OPENAI_BASE_URL} layered on when it is also
 * present — the provider-expansion design's §7 amendment, by which local runtimes (LM Studio,
 * Ollama) and gateways (OpenRouter) become zero-code env citizens. A base URL with no key is not
 * credentials and bootstraps nothing.
 */
public final class OpenAiModelProviderBootstrap implements ModelProviderBootstrap {

  static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
  static final String BASE_URL_ENV_VAR = "OPENAI_BASE_URL";
  static final String DEFAULT_MODEL_ID = "gpt-4o-mini";

  public OpenAiModelProviderBootstrap() {}

  @Override
  public String name() {
    return "openai";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(API_KEY_ENV_VAR, BASE_URL_ENV_VAR);
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
    var baseUrl = env.get(BASE_URL_ENV_VAR);
    return Optional.of(
        OpenAiModelProvider.create(
            c -> {
              c.apiKey(apiKey);
              if (baseUrl != null) {
                c.baseUrl(baseUrl);
              }
            }));
  }
}
