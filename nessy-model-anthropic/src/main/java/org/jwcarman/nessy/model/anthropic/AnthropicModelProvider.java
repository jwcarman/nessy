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
package org.jwcarman.nessy.model.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import java.util.Set;
import java.util.function.Predicate;
import org.jwcarman.nessy.model.anthropic.AnthropicRequests.ThinkingConfig;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The public face of the Anthropic provider module: turns a {@link ModelRequest} into a live
 * streaming call against the anthropic-java SDK.
 *
 * <p>Everything upstream of this class is pure translation ({@link AnthropicRequests}, {@link
 * AnthropicStream}, {@link AnthropicSchemas}); this class is the one place that owns an {@link
 * AnthropicClient} and actually talks to the network.
 */
public final class AnthropicModelProvider implements ModelProvider {

  private static final Set<Capability> CAPABILITIES =
      Set.of(
          Capability.THINKING,
          Capability.PROMPT_CACHING,
          Capability.PARALLEL_TOOL_CALLS,
          Capability.IMAGE_INPUT);

  /**
   * Which failures {@link org.jwcarman.nessy.spi.model.RetryingModelProvider} should retry.
   *
   * <p>Grounded in the SDK's own retry classification: the anthropic-java SDK's internal HTTP
   * client retries a raw {@link java.io.IOException} or {@link AnthropicRetryableException}
   * unconditionally, and otherwise retries by status code (429, or any 5xx, which includes the 529
   * "overloaded" status Anthropic uses) <em>before</em> that response is ever translated into one
   * of the typed exceptions below — so by the time a status-code-driven retry is visible to
   * application code as an exception, the SDK's own retry budget ({@code maxRetries}, default 2)
   * has already been exhausted. What remains retryable from here:
   *
   * <p>Retryable: {@link RateLimitException} (429), {@link InternalServerException} (every 5xx the
   * SDK maps explicitly, which includes the 529 "overloaded" status Anthropic uses — 500..599 is
   * one dispatch branch in the SDK's error handler), {@link AnthropicIoException} (the SDK's
   * wrapper for transport-level failures — connect/read/write errors below the HTTP layer), and
   * {@link AnthropicRetryableException} (the SDK's own marker for a transient failure it identified
   * internally, e.g. during its own retry bookkeeping) — each can still surface after the SDK's own
   * retry budget runs out, and a further caller-driven retry (e.g. with backoff) is still
   * appropriate.
   *
   * <p>Not retryable: everything the SDK maps to a specific 4xx ({@code BadRequestException} 400,
   * {@code UnauthorizedException} 401, {@code PermissionDeniedException} 403, {@code
   * NotFoundException} 404, {@code UnprocessableEntityException} 422) — these mean the request
   * itself is wrong and retrying it unchanged only repeats the failure — plus {@code
   * UnexpectedStatusCodeException} (any status the SDK does not otherwise recognize, treated as
   * "unknown" rather than assumed transient), {@code SseException} (a malformed streaming
   * response), {@code AnthropicInvalidDataException} (a deserialization problem, not a network
   * one), and the credential-resolution family ({@code CredentialResolutionException}, {@code
   * NoCredentialsException}) and {@code AnthropicWebhookException}, none of which a retry can fix.
   */
  public static final Predicate<RuntimeException> RETRYABLE =
      e ->
          e instanceof RateLimitException
              || e instanceof InternalServerException
              || e instanceof AnthropicIoException
              || e instanceof AnthropicRetryableException;

  private final AnthropicClient client;
  private final int thinkingBudget;

  private AnthropicModelProvider(AnthropicClient client, int thinkingBudget) {
    this.client = client;
    this.thinkingBudget = thinkingBudget;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    var params = AnthropicRequests.toParams(request, thinkingConfigFor(request));
    return new AnthropicStream(client.messages().createStreaming(params));
  }

  @Override
  public Set<Capability> capabilities() {
    return CAPABILITIES;
  }

  private ThinkingConfig thinkingConfigFor(ModelRequest request) {
    return new ThinkingConfig(request.requested().contains(Capability.THINKING), thinkingBudget);
  }

  /** Assembles an {@link AnthropicModelProvider}. */
  public static final class Builder {

    // Anthropic's floor for the thinking budget. AgentBuilder.DEFAULT_MAX_TOKENS is 4096, and
    // AnthropicRequests.toParams requires maxTokens to exceed the thinking budget, so the default
    // here must stay comfortably under that default headroom — the lowest value the API accepts
    // is also the only one guaranteed to leave room. A caller who wants a larger default thinking
    // budget must also raise AgentBuilder.maxTokens(...) to keep the two in the same order.
    private static final int DEFAULT_THINKING_BUDGET = 1024;
    private static final String API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";
    private static final String AUTH_TOKEN_ENV_VAR = "ANTHROPIC_AUTH_TOKEN";

    private String apiKey;
    private String baseUrl;
    private int thinkingBudget = DEFAULT_THINKING_BUDGET;
    private AnthropicClient client;
    private boolean useEnv;

    private Builder() {}

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Delegates credential and configuration resolution to the SDK's own {@link
     * AnthropicOkHttpClient.Builder#fromEnv()} rather than reading {@value #API_KEY_ENV_VAR}
     * ourselves, so every environment source the SDK understands is honored — not just the API key:
     * {@value #AUTH_TOKEN_ENV_VAR}, {@code ANTHROPIC_BASE_URL}, profile files, and
     * workload-identity federation.
     *
     * <p>Only a flag is set here; nothing is read yet. {@link #build()} applies it by calling the
     * SDK's {@code fromEnv()} first, then layering any explicit {@link #apiKey(String)} / {@link
     * #baseUrl(String)} set on <em>this</em> builder on top — an explicit override always wins over
     * an ambient environment value.
     *
     * @throws IllegalStateException at {@link #build()} time if neither an explicit key nor {@value
     *     #API_KEY_ENV_VAR} / {@value #AUTH_TOKEN_ENV_VAR} is available. (Credentials that come
     *     only from a profile file or workload-identity federation are not checked here and are
     *     trusted entirely to the SDK's own resolution — see {@link #build()}.)
     */
    public Builder fromEnv() {
      this.useEnv = true;
      return this;
    }

    /** Overrides the API base URL — for proxies or Anthropic-compatible gateways. */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * The extended-thinking token budget used when a request asks for {@link Capability#THINKING}.
     */
    public Builder thinkingBudget(int thinkingBudget) {
      this.thinkingBudget = thinkingBudget;
      return this;
    }

    /**
     * Escape hatch: supply a fully preconfigured SDK client instead of {@code apiKey}/{@code
     * baseUrl}.
     */
    public Builder client(AnthropicClient client) {
      this.client = client;
      return this;
    }

    public AnthropicModelProvider build() {
      if (client != null) {
        return new AnthropicModelProvider(client, thinkingBudget);
      }
      if (useEnv) {
        return new AnthropicModelProvider(buildFromEnv(), thinkingBudget);
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
      return new AnthropicModelProvider(clientBuilder.build(), thinkingBudget);
    }

    /**
     * Builds through the SDK's own {@code fromEnv()}, with this builder's explicit {@code apiKey} /
     * {@code baseUrl} (if set) layered on top afterward so they win over whatever the environment
     * supplied.
     *
     * <p>The SDK's {@code fromEnv()}/{@code build()} do not themselves throw when no credential
     * source resolves — a client-less-of-credentials still builds, and the failure only surfaces as
     * an authentication error on the first real request. {@value #API_KEY_ENV_VAR} and {@value
     * #AUTH_TOKEN_ENV_VAR} are checked directly here so the common "nothing is configured" case
     * still fails fast at {@code build()} with a message naming the variable, matching the
     * friendly-error behavior of the {@code apiKey}-only path above. Any other failure the SDK does
     * raise while resolving (a malformed profile file, for instance) is caught and rethrown in the
     * same friendly shape.
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
}
