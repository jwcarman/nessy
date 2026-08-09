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
   * <p>Retryable: {@link RateLimitException} (429), {@link InternalServerException} (every 5xx the
   * SDK maps explicitly, which includes the 529 "overloaded" status Anthropic uses — 500..599 is
   * one dispatch branch in the SDK's error handler), {@link AnthropicIoException} (the SDK's
   * wrapper for transport-level failures — connect/read/write errors below the HTTP layer), and
   * {@link AnthropicRetryableException} (the SDK's own marker for a transient failure it identified
   * internally, e.g. during its own retry bookkeeping).
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

    private static final int DEFAULT_THINKING_BUDGET = 8192;
    private static final String API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";

    private String apiKey;
    private String baseUrl;
    private int thinkingBudget = DEFAULT_THINKING_BUDGET;
    private AnthropicClient client;

    private Builder() {}

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Reads the API key from the {@value #API_KEY_ENV_VAR} environment variable.
     *
     * @throws IllegalStateException if the variable is unset or blank
     */
    public Builder fromEnv() {
      var value = System.getenv(API_KEY_ENV_VAR);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(
            API_KEY_ENV_VAR + " environment variable is not set; call apiKey(...) instead");
      }
      this.apiKey = value;
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
  }
}
