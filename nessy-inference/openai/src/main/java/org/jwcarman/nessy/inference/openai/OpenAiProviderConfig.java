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
package org.jwcarman.nessy.inference.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.Timeout;
import java.time.Duration;
import java.util.Objects;
import org.jwcarman.nessy.api.Customizer;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link OpenAiInferenceProvider#of(Customizer)} hands a customizer: a CONFIG, not a builder
 * (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}.
 */
public final class OpenAiProviderConfig {

  private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

  private String apiKey;
  private String baseUrl;
  private String organization;
  private OpenAIClient client;
  private boolean useEnv;
  private String vendor = OpenAiInferenceProvider.VENDOR;
  private Duration timeout;

  /**
   * Reads a tool's schema back from the JSON text an {@code JsonSchema} carries.
   *
   * <p>A default rather than a hidden global: it is one field on this config, so an application
   * that has configured its own mapper hands that one over instead of discovering later that an
   * adapter built its own behind its back.
   */
  private JsonMapper mapper = JsonMapper.builder().build();

  OpenAiProviderConfig() {}

  public OpenAiProviderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * Delegates credential and configuration resolution to the SDK's own {@link
   * OpenAIOkHttpClient.Builder#fromEnv()} rather than reading {@value #API_KEY_ENV_VAR} ourselves,
   * so every environment source the SDK understands is honored — not just the API key: {@code
   * OPENAI_ORG_ID}, {@code OPENAI_PROJECT_ID}, {@code OPENAI_BASE_URL}, {@code
   * OPENAI_WEBHOOK_SECRET}, {@code OPENAI_ADMIN_KEY}, {@code OPENAI_CUSTOM_HEADERS}, and the {@code
   * AZURE_OPENAI_KEY} Azure-credential path.
   *
   * <p>Only a flag is set here; nothing is read yet. {@code build()} applies it by calling the
   * SDK's {@code fromEnv()} first, then layering any explicit {@link #apiKey(String)} / {@link
   * #baseUrl(String)} / {@link #organization(String)} set on <em>this</em> config on top — an
   * explicit override always wins over an ambient environment value.
   *
   * @throws IllegalStateException at {@code build()} time if neither an explicit key nor {@value
   *     #API_KEY_ENV_VAR} is available. (Azure's {@code AZURE_OPENAI_KEY} credential path is not
   *     checked here and is trusted entirely to the SDK's own resolution — see {@code build()}.)
   */
  public OpenAiProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /**
   * Overrides the API base URL — the breadth feature that lets this provider talk to any
   * OpenAI-compatible endpoint, not just OpenAI itself: OpenRouter ({@code
   * https://openrouter.ai/api/v1}), a local Ollama server ({@code http://localhost:11434/v1}), or a
   * proxy/gateway.
   */
  public OpenAiProviderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  /** The {@code OpenAI-Organization} header value, for accounts that belong to multiple orgs. */
  public OpenAiProviderConfig organization(String organization) {
    this.organization = organization;
    return this;
  }

  /**
   * Escape hatch: supply a fully preconfigured SDK client instead of {@code apiKey}/{@code
   * baseUrl}/{@code organization}.
   *
   * <p><b>Ownership stays with the caller.</b> {@link OpenAiInferenceProvider#close()} closes only
   * a client it built itself; a client supplied here is never closed by the provider.
   */
  public OpenAiProviderConfig client(OpenAIClient client) {
    this.client = client;
    return this;
  }

  public OpenAiProviderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * The maximum time to wait on the SDK's underlying HTTP call, applied as {@link
   * Timeout#request()} — connect stays at the SDK's own one-minute default; only the request bound
   * moves. Unset by default, so an application that never calls this keeps the SDK's own ten-minute
   * request timeout (openai-java 4.50.0) — a hung call costing ten minutes is the bug this setter
   * exists to fix, and the starter that builds this provider by default sets it to a margin above
   * the engine's own {@code InferenceConfig.timeout}.
   *
   * <p>Ignored when a preconfigured {@link #client(OpenAIClient)} is supplied: that client is used
   * exactly as given, and whatever timeout it already carries is the caller's own business, not
   * this config's to override.
   *
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public OpenAiProviderConfig timeout(Duration timeout) {
    this.timeout = requirePositive(timeout);
    return this;
  }

  private static Duration requirePositive(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive, was " + timeout);
    }
    return timeout;
  }

  /**
   * The vendor name this provider reports in spans ({@code gen_ai.provider.name}): {@code openai}
   * unless the same wire is being spoken to somebody else, as it is for xAI.
   */
  public OpenAiProviderConfig vendor(String vendor) {
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    return this;
  }

  /**
   * Turns this config into the {@link OpenAiInferenceProvider} it describes — the factory's own
   * step, never a public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * OpenAiInferenceProvider#create(Customizer<OpenAiProviderConfig>)}, once {@code customize} has
   * returned.
   */
  OpenAiInferenceProvider build() {
    if (client != null) {
      return new OpenAiInferenceProvider(client, vendor, false, mapper);
    }
    if (useEnv) {
      return new OpenAiInferenceProvider(buildFromEnv(), vendor, true, mapper);
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
              + " client via client(...)");
    }
    var clientBuilder = OpenAIOkHttpClient.builder().apiKey(apiKey);
    if (baseUrl != null) {
      clientBuilder.baseUrl(baseUrl);
    }
    if (organization != null) {
      clientBuilder.organization(organization);
    }
    if (timeout != null) {
      clientBuilder.timeout(Timeout.builder().request(timeout).build());
    }
    return new OpenAiInferenceProvider(clientBuilder.build(), vendor, true, mapper);
  }

  /**
   * Builds through the SDK's own {@code fromEnv()}, with this config's explicit {@code apiKey} /
   * {@code baseUrl} / {@code organization} (if set) layered on top afterward so they win over
   * whatever the environment supplied.
   *
   * <p>Unlike the Anthropic SDK (which defers a missing-credential failure to the first real
   * request), this SDK's own {@code ClientOptions.Builder.build()} already fails fast: it resolves
   * an {@code effectiveCredential()} synchronously and throws {@code IllegalStateException}
   * immediately when no credential source (API key, workload identity, or admin key) is configured.
   * {@value #API_KEY_ENV_VAR} is still checked directly here, ahead of calling the SDK, so that
   * common "nothing is configured" case produces our own friendly, consistently shaped message
   * naming the variable and the {@code apiKey(...)}/{@code client(...)} alternatives — the same
   * shape as the {@code apiKey}-only path above — rather than the SDK's generic credential-source
   * message. Any other failure the SDK does raise while resolving (e.g. both {@value
   * #API_KEY_ENV_VAR} and {@code AZURE_OPENAI_KEY} set at once) is caught and rethrown in that same
   * friendly shape.
   */
  private OpenAIClient buildFromEnv() {
    if (apiKey == null && System.getenv(API_KEY_ENV_VAR) == null) {
      throw missingEnvCredentials();
    }
    try {
      var sdkBuilder = OpenAIOkHttpClient.builder().fromEnv();
      if (apiKey != null) {
        sdkBuilder.apiKey(apiKey);
      }
      if (baseUrl != null) {
        sdkBuilder.baseUrl(baseUrl);
      }
      if (organization != null) {
        sdkBuilder.organization(organization);
      }
      if (timeout != null) {
        sdkBuilder.timeout(Timeout.builder().request(timeout).build());
      }
      return sdkBuilder.build();
    } catch (RuntimeException e) {
      throw new IllegalStateException("could not resolve credentials from the environment", e);
    }
  }

  private static IllegalStateException missingEnvCredentials() {
    var message =
        API_KEY_ENV_VAR
            + " environment variable is not set; call apiKey(...) or client(...) instead";
    return new IllegalStateException(message);
  }
}
