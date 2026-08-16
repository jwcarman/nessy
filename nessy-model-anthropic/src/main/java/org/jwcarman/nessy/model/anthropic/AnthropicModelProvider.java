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
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import java.util.Objects;
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

  AnthropicModelProvider(AnthropicClient client, int thinkingBudget) {
    this.client = client;
    this.thinkingBudget = thinkingBudget;
  }

  /**
   * The blessed one-call shape: equivalent to {@code create(AnthropicProviderConfig::fromEnv)}.
   * Delegates credential and configuration resolution to the SDK's own environment table — see
   * {@link AnthropicProviderConfig#fromEnv()}.
   */
  public static AnthropicModelProvider fromEnv() {
    return create(AnthropicProviderConfig::fromEnv);
  }

  /**
   * Builds an {@link AnthropicModelProvider} from a live {@link AnthropicProviderConfig}: {@code
   * customizer} fills it in, then this factory validates its required field and constructs the
   * finished provider. No public {@code build()} survives here; the factory is the only place an
   * {@link AnthropicProviderConfig} ever turns into an {@link AnthropicModelProvider} (design of
   * record 2026-08-16 §1).
   */
  public static AnthropicModelProvider create(AnthropicProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    AnthropicProviderConfig config = new AnthropicProviderConfig();
    customizer.customize(config);
    return config.build();
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

  @Override
  public String name() {
    return "Anthropic";
  }

  private ThinkingConfig thinkingConfigFor(ModelRequest request) {
    return new ThinkingConfig(request.requested().contains(Capability.THINKING), thinkingBudget);
  }
}
