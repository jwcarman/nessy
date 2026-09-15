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
package org.jwcarman.nessy.inference.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link AnthropicInferenceProvider#create(AnthropicProviderCustomizer)} hands a customizer: a
 * CONFIG, not a builder (design of record 2026-08-16 §1) — fluent setters, no public {@code
 * build()}.
 */
public final class AnthropicProviderConfig {

  // Anthropic's floor for the thinking budget. AgentConfig.DEFAULT_MAX_TOKENS is 4096, and
  // AnthropicRequests.toParams requires maxTokens to exceed the thinking budget, so the default
  // here must stay comfortably under that default headroom — the lowest value the API accepts
  // is also the only one guaranteed to leave room. A caller who wants a larger default thinking
  // budget must also raise AgentConfig.maxTokens(...) to keep the two in the same order.
  private static final int DEFAULT_THINKING_BUDGET = 1024;
  private static final String API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";
  private static final String AUTH_TOKEN_ENV_VAR = "ANTHROPIC_AUTH_TOKEN";

  private String apiKey;
  private String baseUrl;
  private boolean thinking;
  private int thinkingBudget = DEFAULT_THINKING_BUDGET;
  private PromptCaching promptCaching = PromptCaching.OFF;
  private AnthropicClient client;
  private boolean useEnv;

  /**
   * Reads the JSON text that schemas, arguments and provider payloads travel as.
   *
   * <p>A default rather than a hidden global: it is one field here, so an application that has
   * configured its own mapper hands that one over instead of discovering later that an adapter
   * built its own behind its back.
   */
  private JsonMapper mapper = JsonMapper.builder().build();

  AnthropicProviderConfig() {}

  public AnthropicProviderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * Delegates credential and configuration resolution to the SDK's own {@link
   * AnthropicOkHttpClient.Builder#fromEnv()} rather than reading {@value #API_KEY_ENV_VAR}
   * ourselves, so every environment source the SDK understands is honored — not just the API key:
   * {@value #AUTH_TOKEN_ENV_VAR}, {@code ANTHROPIC_BASE_URL}, profile files, and workload-identity
   * federation.
   *
   * <p>Only a flag is set here; nothing is read yet. {@link #build()} applies it by calling the
   * SDK's {@code fromEnv()} first, then layering any explicit {@link #apiKey(String)} / {@link
   * #baseUrl(String)} set on <em>this</em> config on top — an explicit override always wins over an
   * ambient environment value.
   *
   * @throws IllegalStateException at {@link #build()} time if neither an explicit key nor {@value
   *     #API_KEY_ENV_VAR} / {@value #AUTH_TOKEN_ENV_VAR} is available. (Credentials that come only
   *     from a profile file or workload-identity federation are not checked here and are trusted
   *     entirely to the SDK's own resolution — see {@link #build()}.)
   */
  public AnthropicProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** Overrides the API base URL — for proxies or Anthropic-compatible gateways. */
  public AnthropicProviderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  /**
   * Extended thinking on every call. Off by default: reasoning is spent out of each call's {@code
   * maxTokens}, which must then exceed {@link #thinkingBudget(int) the budget}.
   */
  public AnthropicProviderConfig thinking(boolean thinking) {
    this.thinking = thinking;
    return this;
  }

  /** The extended-thinking token budget, when {@link #thinking(boolean) thinking} is on. */
  public AnthropicProviderConfig thinkingBudget(int thinkingBudget) {
    this.thinkingBudget = thinkingBudget;
    return this;
  }

  /** Prompt caching on every call. Off by default; see {@link PromptCaching}. */
  public AnthropicProviderConfig promptCaching(PromptCaching promptCaching) {
    this.promptCaching = Objects.requireNonNull(promptCaching, "promptCaching must not be null");
    return this;
  }

  private AnthropicRequests.Features features() {
    return new AnthropicRequests.Features(thinking, thinkingBudget, promptCaching);
  }

  /**
   * Escape hatch: supply a fully preconfigured SDK client instead of {@code apiKey}/{@code
   * baseUrl}.
   *
   * <p><b>Ownership stays with the caller.</b> {@link AnthropicInferenceProvider#close()} closes
   * only a client it built itself; a client supplied here is never closed by the provider.
   */
  public AnthropicProviderConfig client(AnthropicClient client) {
    this.client = client;
    return this;
  }

  public AnthropicProviderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * Turns this config into the {@link AnthropicInferenceProvider} it describes — the factory's own
   * step, never a public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * AnthropicInferenceProvider#create(AnthropicProviderCustomizer)}, once {@code customize} has
   * returned.
   */
  AnthropicInferenceProvider build() {
    if (client != null) {
      return new AnthropicInferenceProvider(client, features(), false, mapper);
    }
    if (useEnv) {
      return new AnthropicInferenceProvider(buildFromEnv(), features(), true, mapper);
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
              + " client via client(...)");
    }
    var clientBuilder = AnthropicOkHttpClient.builder().apiKey(apiKey);
    if (baseUrl != null) {
      clientBuilder.baseUrl(baseUrl);
    }
    return new AnthropicInferenceProvider(clientBuilder.build(), features(), true, mapper);
  }

  /**
   * Builds through the SDK's own {@code fromEnv()}, with this config's explicit {@code apiKey} /
   * {@code baseUrl} (if set) layered on top afterward so they win over whatever the environment
   * supplied.
   *
   * <p>The SDK's {@code fromEnv()}/{@code build()} do not themselves throw when no credential
   * source resolves — a client-less-of-credentials still builds, and the failure only surfaces as
   * an authentication error on the first real request. {@value #API_KEY_ENV_VAR} and {@value
   * #AUTH_TOKEN_ENV_VAR} are checked directly here so the common "nothing is configured" case still
   * fails fast at {@code build()} with a message naming the variable, matching the friendly-error
   * behavior of the {@code apiKey}-only path above. Any other failure the SDK does raise while
   * resolving (a malformed profile file, for instance) is caught and rethrown in the same friendly
   * shape.
   */
  private AnthropicClient buildFromEnv() {
    if (apiKey == null
        && System.getenv(API_KEY_ENV_VAR) == null
        && System.getenv(AUTH_TOKEN_ENV_VAR) == null) {
      throw missingEnvCredentials();
    }
    try {
      var sdkBuilder = AnthropicOkHttpClient.builder().fromEnv();
      if (apiKey != null) {
        sdkBuilder.apiKey(apiKey);
      }
      if (baseUrl != null) {
        sdkBuilder.baseUrl(baseUrl);
      }
      return sdkBuilder.build();
    } catch (RuntimeException e) {
      throw new IllegalStateException("could not resolve credentials from the environment", e);
    }
  }

  private static IllegalStateException missingEnvCredentials() {
    var message =
        API_KEY_ENV_VAR
            + " (or "
            + AUTH_TOKEN_ENV_VAR
            + ") environment variable is not set; call apiKey(...) or client(...) instead";
    return new IllegalStateException(message);
  }
}
