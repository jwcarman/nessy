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
package org.jwcarman.nessy.inference.gemini;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.VendorProperties;
import org.jwcarman.nessy.api.VendorProperty;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link GeminiInferenceProvider#of(Customizer)} hands a customizer: a CONFIG, not a builder
 * -- fluent setters, no public {@code build()}.
 */
public final class GeminiProviderConfig {

  private static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
  private static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";

  private String apiKey;
  private String baseUrl;
  private Client client;
  private boolean useEnv;
  private Duration timeout;
  private JsonMapper mapper = JsonMapper.builder().build();
  private final Map<String, String> properties = new LinkedHashMap<>();

  GeminiProviderConfig() {}

  public GeminiProviderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * Reads {@value #GEMINI_API_KEY_ENV_VAR} then, if that is unset, {@value
   * #GOOGLE_API_KEY_ENV_VAR}, Google's own documented pair in that order. Only a flag is set here;
   * {@link #build()} applies it, with an explicit {@link #apiKey(String)} winning over either
   * variable.
   */
  public GeminiProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** Overrides the API base URL, for proxies, gateways or Gemini-compatible endpoints. */
  public GeminiProviderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  /**
   * Escape hatch: a fully preconfigured SDK client instead of {@code apiKey}/{@code baseUrl}.
   *
   * <p><b>Ownership stays with the caller.</b> The provider closes only a client it built itself.
   */
  public GeminiProviderConfig client(Client client) {
    this.client = client;
    return this;
  }

  public GeminiProviderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * The maximum time to wait on the underlying HTTP call, applied as {@link HttpOptions#timeout()}
   * -- which the SDK uses as OkHttp's {@code callTimeout}. Unset by default: an unconfigured Gemini
   * client has {@code connectTimeout}/{@code readTimeout}/{@code writeTimeout} all zeroed by the
   * SDK itself ({@code ApiClient}'s "Remove timeouts by default") and no {@code callTimeout}
   * either, so a hung call hangs forever -- the bug this setter exists to fix. The starter that
   * builds this provider by default sets it to a margin above the engine's own {@code
   * InferenceConfig.timeout}.
   *
   * <p>Ignored when a preconfigured {@link #client(Client)} is supplied: that client is used
   * exactly as given, and whatever timeout it already carries is the caller's own business, not
   * this config's to override.
   *
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public GeminiProviderConfig timeout(Duration timeout) {
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
   * One vendor property for every agent type on this provider, spelled as the Gemini REST reference
   * spells it: {@code gemini.generationConfig.thinkingConfig.thinkingBudget}. An agent type's entry
   * of the same name overrides it. Repeatable; the last value given for a name wins. A name outside
   * {@code gemini.} or a bad value fails at build; an unsupported name under {@code gemini.} is
   * ignored, with a warning naming it.
   */
  public GeminiProviderConfig property(String name, String value) {
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
  public <T> GeminiProviderConfig property(VendorProperty<T> property, T value) {
    return property(property.name(), property.format(value));
  }

  /** Every entry of {@code properties}, as if by {@link #property(String, String)}. */
  public GeminiProviderConfig properties(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    properties.forEach(this::property);
    return this;
  }

  GeminiInferenceProvider build() {
    GeminiPropertyReader.requireOwn(properties);
    GeminiPropertyReader.read(properties);
    GeminiPropertyReader.warnUnsupported(properties);
    return new GeminiInferenceProvider(
        resolveClient(), mapper, Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
  }

  private GeminiClient resolveClient() {
    if (client != null) {
      return GeminiClient.over(client, false);
    }
    String key = apiKey;
    if (useEnv && key == null) {
      key = System.getenv(GEMINI_API_KEY_ENV_VAR);
      if (key == null) {
        key = System.getenv(GOOGLE_API_KEY_ENV_VAR);
      }
      if (key == null) {
        throw new IllegalStateException(
            GEMINI_API_KEY_ENV_VAR
                + " (or "
                + GOOGLE_API_KEY_ENV_VAR
                + ") environment variable is not set; call apiKey(...) or client(...) instead");
      }
    }
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
              + " client via client(...)");
    }
    Client.Builder builder = Client.builder().apiKey(key);
    if (baseUrl != null || timeout != null) {
      HttpOptions.Builder httpOptions = HttpOptions.builder();
      if (baseUrl != null) {
        httpOptions.baseUrl(baseUrl);
      }
      if (timeout != null) {
        httpOptions.timeout((int) timeout.toMillis());
      }
      builder.httpOptions(httpOptions.build());
    }
    return GeminiClient.over(builder.build(), true);
  }
}
