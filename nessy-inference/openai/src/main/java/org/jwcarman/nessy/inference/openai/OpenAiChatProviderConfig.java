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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.vendor.VendorProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link OpenAiChatInferenceProvider#of(Customizer)} hands a customizer: a CONFIG, not a
 * builder (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}.
 */
public final class OpenAiChatProviderConfig {

  private String apiKey;
  private String baseUrl;
  private String organization;
  private OpenAIClient client;
  private boolean useEnv;
  private String vendor = OpenAiChatInferenceProvider.VENDOR;
  private Duration timeout;
  private final Map<String, String> properties = new LinkedHashMap<>();

  /**
   * Reads a tool's schema back from the JSON text an {@code JsonSchema} carries.
   *
   * <p>A default rather than a hidden global: it is one field on this config, so an application
   * that has configured its own mapper hands that one over instead of discovering later that an
   * adapter built its own behind its back.
   */
  private JsonMapper mapper = JsonMapper.builder().build();

  OpenAiChatProviderConfig() {}

  public OpenAiChatProviderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * Delegates credential and configuration resolution to the SDK's own {@link
   * OpenAIOkHttpClient.Builder#fromEnv()} rather than reading {@code OPENAI_API_KEY} ourselves, so
   * every environment source the SDK understands is honored — not just the API key: {@code
   * OPENAI_ORG_ID}, {@code OPENAI_PROJECT_ID}, {@code OPENAI_BASE_URL}, {@code
   * OPENAI_WEBHOOK_SECRET}, {@code OPENAI_ADMIN_KEY}, {@code OPENAI_CUSTOM_HEADERS}, and the {@code
   * AZURE_OPENAI_KEY} Azure-credential path.
   *
   * <p>Only a flag is set here; nothing is read yet. {@code build()} applies it by calling the
   * SDK's {@code fromEnv()} first, then layering any explicit {@link #apiKey(String)} / {@link
   * #baseUrl(String)} / {@link #organization(String)} set on <em>this</em> config on top — an
   * explicit override always wins over an ambient environment value.
   *
   * @throws IllegalStateException at {@code build()} time if neither an explicit key nor {@code
   *     OPENAI_API_KEY} is available. (Azure's {@code AZURE_OPENAI_KEY} credential path is not
   *     checked here and is trusted entirely to the SDK's own resolution — see {@code build()}.)
   */
  public OpenAiChatProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /**
   * Overrides the API base URL — the breadth feature that lets this provider talk to any
   * OpenAI-compatible endpoint, not just OpenAI itself: OpenRouter ({@code
   * https://openrouter.ai/api/v1}), a local Ollama server ({@code http://localhost:11434/v1}), or a
   * proxy/gateway.
   */
  public OpenAiChatProviderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  /** The {@code OpenAI-Organization} header value, for accounts that belong to multiple orgs. */
  public OpenAiChatProviderConfig organization(String organization) {
    this.organization = organization;
    return this;
  }

  /**
   * Escape hatch: supply a fully preconfigured SDK client instead of {@code apiKey}/{@code
   * baseUrl}/{@code organization}.
   *
   * <p><b>Ownership stays with the caller.</b> {@link OpenAiChatInferenceProvider#close()} closes
   * only a client it built itself; a client supplied here is never closed by the provider.
   */
  public OpenAiChatProviderConfig client(OpenAIClient client) {
    this.client = client;
    return this;
  }

  public OpenAiChatProviderConfig mapper(JsonMapper mapper) {
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
  public OpenAiChatProviderConfig timeout(Duration timeout) {
    this.timeout = OpenAiClients.requirePositive(timeout);
    return this;
  }

  /**
   * The vendor name this provider reports in spans ({@code gen_ai.provider.name}): {@code openai}
   * unless the same wire is being spoken to somebody else, as it is for xAI.
   */
  public OpenAiChatProviderConfig vendor(String vendor) {
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    return this;
  }

  /**
   * A vendor property this provider sends with every request (spec §6a) -- {@code
   * openai.tools.strict}, {@code openai.reasoning.effort}, or any request field under {@code
   * openai.}, passed through. An agent type's own property of the same name overrides it.
   * Repeatable; the last value given for a name wins. A name under another prefix, or one that
   * names what a typed setting decides, fails at build.
   */
  public OpenAiChatProviderConfig property(String name, String value) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    properties.put(name, VendorProperties.requireString(name, value));
    return this;
  }

  /** {@link #property(String, String)} for each entry, as Boot binds them. */
  public OpenAiChatProviderConfig properties(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    properties.forEach(this::property);
    return this;
  }

  /**
   * Turns this config into the {@link OpenAiChatInferenceProvider} it describes — the factory's own
   * step, never a public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * OpenAiChatInferenceProvider#of(Customizer)}, once {@code customize} has returned.
   */
  OpenAiChatInferenceProvider build() {
    OpenAiProperties.requireOwn(properties);
    OpenAiProperties.chat(properties);
    OpenAiProperties.warnUnsupported(properties);
    Map<String, String> own = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    if (client != null) {
      return new OpenAiChatInferenceProvider(client, vendor, false, mapper, own);
    }
    return new OpenAiChatInferenceProvider(
        OpenAiClients.build(useEnv, apiKey, baseUrl, organization, timeout),
        vendor,
        true,
        mapper,
        own);
  }
}
