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
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.api.VendorProperty;
import org.jwcarman.nessy.vendor.VendorProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link OpenAiResponsesInferenceProvider#of(org.jwcarman.nessy.api.Customizer)} hands a
 * customizer: a config, not a builder -- fluent setters, no public {@code build()}. The same
 * setters as {@link OpenAiChatProviderConfig}, because every one of them is about the client and
 * the vendor tag, not the wire; their full contracts are documented there.
 */
public final class OpenAiResponsesProviderConfig {

  private String apiKey;
  private String baseUrl;
  private String organization;
  private OpenAIClient client;
  private boolean useEnv;
  private String vendor = OpenAiChatInferenceProvider.VENDOR;
  private Duration timeout;
  private JsonMapper mapper = JsonMapper.builder().build();
  private final Map<String, String> properties = new LinkedHashMap<>();

  OpenAiResponsesProviderConfig() {}

  public OpenAiResponsesProviderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /** Reads the SDK's own environment table at build time; anything set explicitly here wins. */
  public OpenAiResponsesProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** Any server that speaks the Responses API, with the {@code /v1} suffix. */
  public OpenAiResponsesProviderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  public OpenAiResponsesProviderConfig organization(String organization) {
    this.organization = organization;
    return this;
  }

  /** A preconfigured client, which stays the caller's: the provider never closes it. */
  public OpenAiResponsesProviderConfig client(OpenAIClient client) {
    this.client = client;
    return this;
  }

  public OpenAiResponsesProviderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * The bound on the SDK's HTTP request; ignored for a supplied client.
   *
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public OpenAiResponsesProviderConfig timeout(Duration timeout) {
    this.timeout = OpenAiClients.requirePositive(timeout);
    return this;
  }

  /**
   * The vendor this provider reports ({@code gen_ai.provider.name}) and tags its reasoning items
   * with.
   */
  public OpenAiResponsesProviderConfig vendor(String vendor) {
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    return this;
  }

  /**
   * A vendor property this provider sends with every request (spec §6a) -- {@code
   * openai.reasoning.effort}, {@code openai.reasoning.summary} or another name the adapter
   * supports. An agent type's own property of the same name overrides it. Repeatable; the last
   * value given for a name wins. A name under another prefix, or a bad value, fails at build; an
   * unsupported name under {@code openai.} is ignored, with a warning naming it.
   */
  public OpenAiResponsesProviderConfig property(String name, String value) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    properties.put(name, VendorProperties.requireString(name, value));
    return this;
  }

  /**
   * {@link #property(String, String)}, with the value typed: it is stored as the text {@code
   * property} writes it as.
   */
  public <T> OpenAiResponsesProviderConfig property(VendorProperty<T> property, T value) {
    return property(property.name(), property.format(value));
  }

  /** {@link #property(String, String)} for each entry, as Boot binds them. */
  public OpenAiResponsesProviderConfig properties(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    properties.forEach(this::property);
    return this;
  }

  OpenAiResponsesInferenceProvider build() {
    OpenAiPropertyReader.requireOwn(properties);
    OpenAiPropertyReader.responses(properties);
    OpenAiPropertyReader.warnUnsupported(properties);
    Map<String, String> own = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    if (client != null) {
      return new OpenAiResponsesInferenceProvider(client, vendor, false, mapper, own);
    }
    return new OpenAiResponsesInferenceProvider(
        OpenAiClients.build(useEnv, apiKey, baseUrl, organization, timeout),
        vendor,
        true,
        mapper,
        own);
  }
}
