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
package org.jwcarman.nessy.model.openai;

import com.openai.client.OpenAIClient;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The public face of the OpenAI provider module: turns a {@link ModelRequest} into a live streaming
 * call against the openai-java SDK's Chat Completions API.
 *
 * <p>Everything upstream of this class is pure translation ({@link OpenAiRequests}, {@link
 * OpenAiStream}); this class is the one place that owns an {@link OpenAIClient} and actually talks
 * to the network.
 */
public final class OpenAiModelProvider implements ModelProvider {

  /**
   * {@link Capability#THINKING} and {@link Capability#PROMPT_CACHING} are deliberately absent.
   *
   * <p>Thinking: Chat Completions has no assistant content type for opaque or extended-reasoning
   * payloads, and OpenAI's reasoning models (the {@code o*}/{@code gpt-5} "thinking" family) do not
   * surface their reasoning as deltas on this wire the way Anthropic's extended thinking does — see
   * {@link OpenAiStream}'s javadoc. Prompt caching: OpenAI's automatic prompt caching has no
   * request-side cache-control field to set (unlike Anthropic's explicit {@code cache_control}
   * blocks) — it is applied server-side, transparently, based on prefix matching, so there is
   * nothing for {@link OpenAiRequests} to opt into.
   */
  private static final Set<Capability> CAPABILITIES =
      Set.of(Capability.PARALLEL_TOOL_CALLS, Capability.IMAGE_INPUT);

  /**
   * Which failures {@link org.jwcarman.nessy.spi.model.RetryingModelProvider} should retry.
   *
   * <p>Grounded in the SDK's own retry classification: {@code
   * com.openai.core.http.RetryingHttpClient} retries a raw {@link java.io.IOException} or {@link
   * OpenAIRetryableException} unconditionally, and otherwise retries by status code (408, 409, 429,
   * or any 5xx) <em>before</em> that response is ever translated into one of the typed {@code
   * OpenAIServiceException} subclasses below — so by the time a status-code-driven retry is visible
   * to application code as an exception, the SDK's own retry budget ({@code maxRetries}, default 2)
   * has already been exhausted. What remains retryable from here:
   *
   * <ul>
   *   <li>{@link RateLimitException} (429) — the SDK retries this itself, but it can still surface
   *       after its own budget runs out, and a further caller-driven retry (e.g. with backoff) is
   *       still appropriate.
   *   <li>{@link InternalServerException} (every 5xx) — same reasoning; a transient server error.
   *   <li>{@link OpenAIIoException} — the SDK's wrapper for transport-level failures
   *       (connect/read/write errors below the HTTP layer).
   *   <li>{@link OpenAIRetryableException} — the SDK's own marker for a transient failure it
   *       identified internally.
   * </ul>
   *
   * <p>Not retryable: {@code BadRequestException} (400), {@code UnauthorizedException} (401),
   * {@code PermissionDeniedException} (403), {@code NotFoundException} (404), {@code
   * UnprocessableEntityException} (422) — these mean the request itself is wrong and retrying it
   * unchanged only repeats the failure — plus {@code UnexpectedStatusCodeException} (any status the
   * SDK does not otherwise recognize, which also covers a 408/409 that survived the SDK's own retry
   * budget, since the error handler has no dedicated exception type for those two codes), {@code
   * SseException} (a malformed streaming response), {@code OpenAIInvalidDataException} (a
   * deserialization problem, not a network one), {@code InvalidWebhookSignatureException} and
   * {@code SubjectTokenProviderException} (workload-identity credential resolution), none of which
   * a retry can fix.
   */
  public static final Predicate<RuntimeException> RETRYABLE =
      e ->
          e instanceof RateLimitException
              || e instanceof InternalServerException
              || e instanceof OpenAIIoException
              || e instanceof OpenAIRetryableException;

  private final OpenAIClient client;

  OpenAiModelProvider(OpenAIClient client) {
    this.client = client;
  }

  /**
   * The blessed one-call shape: equivalent to {@code create(OpenAiProviderConfig::fromEnv)}.
   * Delegates credential and configuration resolution to the SDK's own environment table — see
   * {@link OpenAiProviderConfig#fromEnv()}.
   */
  public static OpenAiModelProvider fromEnv() {
    return create(OpenAiProviderConfig::fromEnv);
  }

  /**
   * Builds an {@link OpenAiModelProvider} from a live {@link OpenAiProviderConfig}: {@code
   * customizer} fills it in, then this factory validates its required field and constructs the
   * finished provider. No public {@code build()} survives here; the factory is the only place an
   * {@link OpenAiProviderConfig} ever turns into an {@link OpenAiModelProvider} (design of record
   * 2026-08-16 §1).
   */
  public static OpenAiModelProvider create(OpenAiProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    OpenAiProviderConfig config = new OpenAiProviderConfig();
    customizer.customize(config);
    return config.build();
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    var params = OpenAiRequests.toParams(request);
    return new OpenAiStream(client.chat().completions().createStreaming(params));
  }

  @Override
  public Set<Capability> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public String name() {
    return "OpenAI";
  }
}
