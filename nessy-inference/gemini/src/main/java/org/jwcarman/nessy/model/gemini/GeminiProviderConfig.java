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

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import java.util.List;

/**
 * What {@link GeminiModelProvider#create(GeminiProviderCustomizer)} hands a customizer: a CONFIG,
 * not a builder (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}.
 */
public final class GeminiProviderConfig {

  private static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
  private static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";

  private String apiKey;
  private String baseUrl;
  private Client client;
  private boolean useEnv;

  GeminiProviderConfig() {}

  public GeminiProviderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * Reads {@value #GEMINI_API_KEY_ENV_VAR} then, if that is unset, {@value #GOOGLE_API_KEY_ENV_VAR}
   * — Google's own documented pair, in that order — itself, rather than delegating to the SDK's own
   * environment resolution the way {@code new Client()} would. This is the seam-integrity rule the
   * env module established: the choice this config makes from the environment is the choice that
   * gets built, not a second, independent read underneath it.
   *
   * <p>Only a flag is set here; nothing is read yet. {@link #build()} applies it, layering any
   * explicit {@link #apiKey(String)} set on <em>this</em> config on top so it wins over either
   * environment variable.
   *
   * @throws IllegalStateException at {@link #build()} time if neither an explicit key nor either
   *     environment variable is available
   */
  public GeminiProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /**
   * Overrides the API base URL — for proxies, gateways, or Gemini-compatible endpoints. Applied via
   * the SDK's {@link HttpOptions#baseUrl()}.
   */
  public GeminiProviderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  /**
   * Escape hatch: supply a fully preconfigured SDK client instead of {@code apiKey}/{@code
   * baseUrl}.
   *
   * <p><b>Ownership stays with the caller.</b> {@link GeminiModelProvider#close()} closes only a
   * client it built itself; a client supplied here is never closed by the provider.
   */
  public GeminiProviderConfig client(Client client) {
    this.client = client;
    return this;
  }

  /**
   * Turns this config into the {@link GeminiModelProvider} it describes — the factory's own step,
   * never a public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * GeminiModelProvider#create(GeminiProviderCustomizer)}, once {@code customize} has returned.
   */
  GeminiModelProvider build() {
    return new GeminiModelProvider(resolveClient());
  }

  private GeminiClient resolveClient() {
    if (client != null) {
      return wrap(client, NOTHING_TO_CLOSE);
    }
    if (useEnv) {
      return wrapOwned(buildFromEnv());
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
              + " client via client(...)");
    }
    return wrapOwned(buildClient(apiKey));
  }

  private Client buildFromEnv() {
    String resolvedKey = apiKey;
    if (resolvedKey == null) {
      resolvedKey = System.getenv(GEMINI_API_KEY_ENV_VAR);
    }
    if (resolvedKey == null) {
      resolvedKey = System.getenv(GOOGLE_API_KEY_ENV_VAR);
    }
    if (resolvedKey == null) {
      throw missingEnvCredentials();
    }
    return buildClient(resolvedKey);
  }

  private Client buildClient(String key) {
    var clientBuilder = Client.builder().apiKey(key);
    if (baseUrl != null) {
      clientBuilder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
    }
    return clientBuilder.build();
  }

  private static IllegalStateException missingEnvCredentials() {
    var message =
        GEMINI_API_KEY_ENV_VAR
            + " (or "
            + GOOGLE_API_KEY_ENV_VAR
            + ") environment variable is not set; call apiKey(...) or client(...) instead";
    return new IllegalStateException(message);
  }

  /**
   * What {@link #wrap} is handed for a client this config did NOT build: a close that releases
   * nothing, because the application that supplied the client still owns it. A gateway must never
   * close what it did not open — the same convention {@code BedrockProviderConfig#wrap} keeps, and
   * the same one a caller-supplied {@code DataSource} keeps everywhere else here.
   */
  static final AutoCloseable NOTHING_TO_CLOSE = () -> {};

  /** The {@code apiKey}/{@code fromEnv()} paths: this config built the client, so it closes it. */
  private static GeminiClient wrapOwned(Client sdkClient) {
    return wrap(sdkClient, sdkClient);
  }

  /**
   * <b>Close ownership, made explicit.</b> {@code onClose} IS the ownership decision: the SDK
   * client itself when this config built it, {@link #NOTHING_TO_CLOSE} when the application handed
   * one in through {@link #client(Client)}. Passing the target rather than a boolean is what makes
   * the decision testable at all — {@code com.google.genai.Client} is a {@code final} class with no
   * observable closed state (the very reason {@link GeminiClient} exists as a seam), so a test
   * proves this by handing in a recording {@link AutoCloseable} instead.
   *
   * <p>An anonymous class rather than the lambda this used to be, because the seam now has two
   * methods and only one of them varies.
   *
   * <p>A close failure is wrapped rather than declared: {@link GeminiClient#close()} throws no
   * checked exception, and neither does {@code Client#close()} — only the {@link AutoCloseable}
   * signature does.
   */
  static GeminiClient wrap(Client sdkClient, AutoCloseable onClose) {
    return new GeminiClient() {

      @Override
      public GeminiStream generateContentStream(
          String model, List<Content> contents, GenerateContentConfig config) {
        var responseStream = sdkClient.models.generateContentStream(model, contents, config);
        return new GeminiStream(responseStream, responseStream::close);
      }

      @Override
      public void close() {
        try {
          onClose.close();
        } catch (Exception e) {
          throw new IllegalStateException("closing the Gemini client failed", e);
        }
      }
    };
  }
}
