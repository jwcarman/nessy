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
import com.anthropic.core.Timeout;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.vendor.VendorProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link AnthropicInferenceProvider#of(Customizer)} hands a customizer: a CONFIG, not a
 * builder (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}.
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
  private Boolean thinking;
  private Integer thinkingBudget;
  private PromptCaching promptCaching;
  private final Map<String, String> properties = new LinkedHashMap<>();
  private AnthropicClient client;
  private boolean useEnv;
  private Duration timeout;

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
   * maxTokens}, which must then exceed {@link #thinkingBudget(int) the budget}. The same statement
   * as {@code anthropic.thinking.type=enabled}; setting both on one provider fails at build, and an
   * agent type's {@code anthropic.thinking.*} property overrides either.
   */
  public AnthropicProviderConfig thinking(boolean thinking) {
    this.thinking = thinking;
    return this;
  }

  /**
   * The extended-thinking token budget, when {@link #thinking(boolean) thinking} is on. The same
   * statement as {@code anthropic.thinking.budget_tokens}; setting both on one provider fails at
   * build. Read only when {@link #thinking(boolean) thinking} is on.
   */
  public AnthropicProviderConfig thinkingBudget(int thinkingBudget) {
    this.thinkingBudget = thinkingBudget;
    return this;
  }

  /**
   * Prompt caching on every call. Off by default; see {@link PromptCaching}. The same statement as
   * {@code anthropic.cache_control.ttl} ({@code 5m}, {@code 1h}); setting both on one provider
   * fails at build.
   */
  public AnthropicProviderConfig promptCaching(PromptCaching promptCaching) {
    this.promptCaching = Objects.requireNonNull(promptCaching, "promptCaching must not be null");
    return this;
  }

  /**
   * A vendor property this provider sends with every request (spec §6a) -- {@code
   * anthropic.thinking.budget_tokens}, {@code anthropic.cache_control.ttl} or another name the
   * adapter supports. An agent type's own property of the same name overrides it. Repeatable; the
   * last value given for a name wins. A name under another prefix, or a bad value, fails at build;
   * an unsupported name under {@code anthropic.} is ignored, with a warning naming it.
   */
  public AnthropicProviderConfig property(String name, String value) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    properties.put(name, VendorProperties.requireString(name, value));
    return this;
  }

  /** {@link #property(String, String)} for each entry, as Boot binds them. */
  public AnthropicProviderConfig properties(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    properties.forEach(this::property);
    return this;
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
   * The maximum time to wait on the SDK's underlying HTTP call, applied as {@link
   * Timeout#request()} — connect stays at the SDK's own one-minute default; only the request bound
   * moves. Unset by default, so an application that never calls this keeps the SDK's own ten-minute
   * request timeout (anthropic-java 2.62.0) — a hung call costing ten minutes is the bug this
   * setter exists to fix, and the starter that builds this provider by default sets it to a margin
   * above the engine's own {@code InferenceConfig.timeout}.
   *
   * <p>Ignored when a preconfigured {@link #client(AnthropicClient)} is supplied: that client is
   * used exactly as given, and whatever timeout it already carries is the caller's own business,
   * not this config's to override.
   *
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public AnthropicProviderConfig timeout(Duration timeout) {
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
   * Turns this config into the {@link AnthropicInferenceProvider} it describes — the factory's own
   * step, never a public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * AnthropicInferenceProvider#create(Customizer<AnthropicProviderConfig>)}, once {@code customize}
   * has returned.
   */
  AnthropicInferenceProvider build() {
    Map<String, String> own = providerProperties();
    if (client != null) {
      return new AnthropicInferenceProvider(client, own, false, mapper);
    }
    if (useEnv) {
      return new AnthropicInferenceProvider(buildFromEnv(), own, true, mapper);
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
    if (timeout != null) {
      clientBuilder.timeout(Timeout.builder().request(timeout).build());
    }
    return new AnthropicInferenceProvider(clientBuilder.build(), own, true, mapper);
  }

  /**
   * The provider's properties with its setters spelled as the properties they mean (plan ruling 8),
   * checked before any client is made. A setter and a property for one field are two provider-level
   * statements with no order between them, so both set is refused.
   */
  private Map<String, String> providerProperties() {
    AnthropicProperties.requireOwn(properties);
    refuseBoth(thinking != null, "thinking(boolean)", AnthropicProperties.THINKING_TYPE);
    refuseBoth(thinkingBudget != null, "thinkingBudget(int)", AnthropicProperties.THINKING_BUDGET);
    // thinking(false) speaks for both thinking names: a lone budget property would turn it on.
    refuseBoth(
        Boolean.FALSE.equals(thinking), "thinking(boolean)", AnthropicProperties.THINKING_BUDGET);
    // A budget with thinking not on is inert, but a type property beside it is a second statement.
    refuseBoth(
        thinkingBudget != null && !Boolean.TRUE.equals(thinking),
        "thinkingBudget(int)",
        AnthropicProperties.THINKING_TYPE);
    refuseBoth(
        promptCaching != null, "promptCaching(PromptCaching)", AnthropicProperties.CACHE_TTL);
    Map<String, String> merged = new LinkedHashMap<>(properties);
    String budgetName = AnthropicProperties.PREFIX + AnthropicProperties.THINKING_BUDGET;
    if (Boolean.TRUE.equals(thinking)) {
      merged.put(
          AnthropicProperties.PREFIX + AnthropicProperties.THINKING_TYPE,
          AnthropicProperties.ENABLED);
      if (thinkingBudget != null) {
        merged.put(budgetName, Integer.toString(thinkingBudget));
      } else if (!merged.containsKey(budgetName)) {
        merged.put(budgetName, Integer.toString(DEFAULT_THINKING_BUDGET));
      }
    }
    if (promptCaching == PromptCaching.FIVE_MINUTES) {
      merged.put(AnthropicProperties.PREFIX + AnthropicProperties.CACHE_TTL, "5m");
    } else if (promptCaching == PromptCaching.ONE_HOUR) {
      merged.put(AnthropicProperties.PREFIX + AnthropicProperties.CACHE_TTL, "1h");
    }
    AnthropicProperties.read(merged);
    AnthropicProperties.warnUnsupported(merged);
    return Collections.unmodifiableMap(merged);
  }

  private void refuseBoth(boolean setterCalled, String setter, String name) {
    if (setterCalled && properties.containsKey(AnthropicProperties.PREFIX + name)) {
      throw new IllegalArgumentException(
          setter
              + " and property '"
              + AnthropicProperties.PREFIX
              + name
              + "' both say how this provider "
              + (name.startsWith("thinking") ? "thinks" : "caches")
              + "; keep one");
    }
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
            + " (or "
            + AUTH_TOKEN_ENV_VAR
            + ") environment variable is not set; call apiKey(...) or client(...) instead";
    return new IllegalStateException(message);
  }
}
