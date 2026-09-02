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
import java.util.function.Predicate;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The vendor gateway for OpenAI: owns the {@link OpenAIClient} and hands out {@link Model} handles
 * bound to one model id apiece via {@link #model(String)}.
 *
 * <p>Everything upstream of the handle it returns is pure translation ({@link OpenAiRequests},
 * {@link OpenAiStream}); this class is the one place that owns the client and actually talks to the
 * network.
 */
public final class OpenAiModelProvider implements ModelProvider, AutoCloseable {

  /**
   * The OpenTelemetry GenAI semantic conventions' default value for this vendor (agentic-o11y spec
   * §1.1). No longer reported through the SPI — {@code Model} describes nothing now — but still the
   * default this gateway carries.
   *
   * <p>Unlike the other vendor gateways, this one is SHARED: an xAI deployment builds this very
   * class against {@code https://api.x.ai/v1}, and semconv has a separate {@code x_ai} value for
   * that. So the provider name is a field given at construction rather than a constant — an xAI
   * turn must not be reported as an OpenAI one. Any OpenAI-compatible endpoint reached through
   * {@link OpenAiProviderConfig#baseUrl(String)} still answers {@code openai}, which is the honest
   * default: nothing else is known about it.
   */
  static final String PROVIDER_NAME = "openai";

  private static final String NAME = "OpenAI";

  /**
   * Which failures a caller wrapping this gateway in a retry should retry.
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
  private final String provider;

  /**
   * Whether {@link #close()} may close {@link #client} — false for a client handed in through
   * {@link OpenAiProviderConfig#client(OpenAIClient)}, which the application still owns. Same
   * convention {@code BedrockModelProvider} already keeps.
   */
  private final boolean ownsClient;

  OpenAiModelProvider(OpenAIClient client) {
    this(client, PROVIDER_NAME, true);
  }

  OpenAiModelProvider(OpenAIClient client, String provider, boolean ownsClient) {
    this.client = client;
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.ownsClient = ownsClient;
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
  public Model model(ModelId id) {
    Objects.requireNonNull(id, "id must not be null");
    return new OpenAiModel(client, id);
  }

  /**
   * Closes the {@link OpenAIClient} this gateway BUILT — its OkHttp connection pool and dispatcher
   * threads. A client handed in through {@link OpenAiProviderConfig#client(OpenAIClient)} is never
   * closed here: it was never opened here. Idempotent, as the SDK's own {@code close()} is.
   */
  @Override
  public void close() {
    if (ownsClient) {
      client.close();
    }
  }

  /** This vendor, by name — no longer an SPI method, kept because callers and logs want it. */
  public String name() {
    return NAME;
  }

  /** A flyweight bound handle: pins one model id over its {@link OpenAIClient}. */
  private record OpenAiModel(OpenAIClient client, ModelId id) implements Model {

    @Override
    public ModelStream stream(ModelRequest request) {
      var params = OpenAiRequests.toParams(request, id.value());
      return new OpenAiStream(client.chat().completions().createStreaming(params));
    }
  }
}
