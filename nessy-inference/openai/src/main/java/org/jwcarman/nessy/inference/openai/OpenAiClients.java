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

/**
 * Builds the SDK client both OpenAI configs hand their adapter: the environment layering, the
 * explicit-override precedence, the missing-credential message and the request timeout, once.
 */
final class OpenAiClients {

  static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

  private OpenAiClients() {}

  /**
   * A client from settings. {@code useEnv} reads the SDK's own environment table first and lays any
   * explicit value over it; otherwise {@code apiKey} is required. Any argument but {@code useEnv}
   * may be null.
   */
  static OpenAIClient build(
      boolean useEnv, String apiKey, String baseUrl, String organization, Duration timeout) {
    if (useEnv) {
      return buildFromEnv(apiKey, baseUrl, organization, timeout);
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
    return clientBuilder.build();
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
   * {@code OPENAI_API_KEY} is still checked directly here, ahead of calling the SDK, so that common
   * "nothing is configured" case produces our own friendly, consistently shaped message naming the
   * variable and the {@code apiKey(...)}/{@code client(...)} alternatives — the same shape as the
   * {@code apiKey}-only path above — rather than the SDK's generic credential-source message. Any
   * other failure the SDK does raise while resolving (e.g. both {@code OPENAI_API_KEY} and {@code
   * AZURE_OPENAI_KEY} set at once) is caught and rethrown in that same friendly shape.
   */
  private static OpenAIClient buildFromEnv(
      String apiKey, String baseUrl, String organization, Duration timeout) {
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

  static Duration requirePositive(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive, was " + timeout);
    }
    return timeout;
  }
}
