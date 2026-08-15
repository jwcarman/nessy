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
package org.jwcarman.nessy.model.env;

import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider follows the key (design §4a, owner: "switching to openai would be simply including
 * that env var"): one method that picks a {@link ModelProvider} by which API key is present in the
 * environment, so an application built against this module switches providers by switching an
 * environment variable rather than its code.
 *
 * <p>{@value #ANTHROPIC_API_KEY_ENV_VAR} present alone chooses Anthropic; {@value
 * #OPENAI_API_KEY_ENV_VAR} present alone chooses OpenAI; both present is broken by {@value
 * #NESSY_PROVIDER_ENV_VAR} ({@code anthropic}/{@code openai}), defaulting to Anthropic with a
 * one-line warning logged when that variable is unset or unrecognized; neither present fails fast,
 * naming exactly the three variables checked. Each provider is built the same way its own module
 * builds one from an explicit key — {@code Provider.builder().apiKey(key).build()} — mirroring
 * {@link AnthropicModelProvider.Builder#apiKey(String)} and {@link
 * OpenAiModelProvider.Builder#apiKey(String)} exactly, rather than each provider's own {@code
 * fromEnv()}, so the choice this class makes from the map handed to it is the choice that is built
 * — not a second, independent read of the real environment underneath it.
 */
public final class EnvModelProviders {

  private static final Logger LOGGER = LoggerFactory.getLogger(EnvModelProviders.class);

  static final String ANTHROPIC_API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";
  static final String OPENAI_API_KEY_ENV_VAR = "OPENAI_API_KEY";
  static final String NESSY_PROVIDER_ENV_VAR = "NESSY_PROVIDER";

  private static final String OPENAI_CHOICE = "openai";
  private static final String ANTHROPIC_CHOICE = "anthropic";

  private EnvModelProviders() {}

  /** The public entry point: chooses a provider from the real process environment. */
  public static ModelProvider fromEnv() {
    return fromEnv(System.getenv());
  }

  /** The offline seam: chooses a provider from {@code env} rather than the real environment. */
  static ModelProvider fromEnv(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    String anthropicKey = env.get(ANTHROPIC_API_KEY_ENV_VAR);
    String openAiKey = env.get(OPENAI_API_KEY_ENV_VAR);
    if (anthropicKey != null && openAiKey != null) {
      return tiebreak(env.get(NESSY_PROVIDER_ENV_VAR), anthropicKey, openAiKey);
    }
    if (anthropicKey != null) {
      return anthropic(anthropicKey);
    }
    if (openAiKey != null) {
      return openai(openAiKey);
    }
    throw new IllegalStateException(
        "no model provider credentials found: set "
            + ANTHROPIC_API_KEY_ENV_VAR
            + " or "
            + OPENAI_API_KEY_ENV_VAR
            + " (and optionally "
            + NESSY_PROVIDER_ENV_VAR
            + " to break a tie if both are set)");
  }

  private static ModelProvider tiebreak(String preference, String anthropicKey, String openAiKey) {
    if (OPENAI_CHOICE.equalsIgnoreCase(preference)) {
      return openai(openAiKey);
    }
    if (!ANTHROPIC_CHOICE.equalsIgnoreCase(preference)) {
      LOGGER.warn(
          "both {} and {} are set; defaulting to Anthropic (set {}=openai to choose OpenAI"
              + " instead)",
          ANTHROPIC_API_KEY_ENV_VAR,
          OPENAI_API_KEY_ENV_VAR,
          NESSY_PROVIDER_ENV_VAR);
    }
    return anthropic(anthropicKey);
  }

  private static ModelProvider anthropic(String apiKey) {
    return AnthropicModelProvider.builder().apiKey(apiKey).build();
  }

  private static ModelProvider openai(String apiKey) {
    return OpenAiModelProvider.builder().apiKey(apiKey).build();
  }
}
