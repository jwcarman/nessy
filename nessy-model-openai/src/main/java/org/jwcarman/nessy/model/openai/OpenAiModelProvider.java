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
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
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

  private OpenAiModelProvider(OpenAIClient client) {
    this.client = client;
  }

  public static Builder builder() {
    return new Builder();
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

  /** Assembles an {@link OpenAiModelProvider}. */
  public static final class Builder {

    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

    private String apiKey;
    private String baseUrl;
    private String organization;
    private OpenAIClient client;
    private boolean useEnv;

    private Builder() {}

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Delegates credential and configuration resolution to the SDK's own {@link
     * OpenAIOkHttpClient.Builder#fromEnv()} rather than reading {@value #API_KEY_ENV_VAR}
     * ourselves, so every environment source the SDK understands is honored — not just the API key:
     * {@code OPENAI_ORG_ID}, {@code OPENAI_PROJECT_ID}, {@code OPENAI_BASE_URL}, {@code
     * OPENAI_WEBHOOK_SECRET}, {@code OPENAI_ADMIN_KEY}, {@code OPENAI_CUSTOM_HEADERS}, and the
     * {@code AZURE_OPENAI_KEY} Azure-credential path.
     *
     * <p>Only a flag is set here; nothing is read yet. {@link #build()} applies it by calling the
     * SDK's {@code fromEnv()} first, then layering any explicit {@link #apiKey(String)} / {@link
     * #baseUrl(String)} / {@link #organization(String)} set on <em>this</em> builder on top — an
     * explicit override always wins over an ambient environment value.
     *
     * @throws IllegalStateException at {@link #build()} time if neither an explicit key nor {@value
     *     #API_KEY_ENV_VAR} is available. (Azure's {@code AZURE_OPENAI_KEY} credential path is not
     *     checked here and is trusted entirely to the SDK's own resolution — see {@link #build()}.)
     */
    public Builder fromEnv() {
      this.useEnv = true;
      return this;
    }

    /**
     * Overrides the API base URL — the breadth feature that lets this provider talk to any
     * OpenAI-compatible endpoint, not just OpenAI itself: OpenRouter ({@code
     * https://openrouter.ai/api/v1}), a local Ollama server ({@code http://localhost:11434/v1}), or
     * a proxy/gateway.
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /** The {@code OpenAI-Organization} header value, for accounts that belong to multiple orgs. */
    public Builder organization(String organization) {
      this.organization = organization;
      return this;
    }

    /**
     * Escape hatch: supply a fully preconfigured SDK client instead of {@code apiKey}/{@code
     * baseUrl}/{@code organization}.
     */
    public Builder client(OpenAIClient client) {
      this.client = client;
      return this;
    }

    public OpenAiModelProvider build() {
      if (client != null) {
        return new OpenAiModelProvider(client);
      }
      if (useEnv) {
        return new OpenAiModelProvider(buildFromEnv());
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
      return new OpenAiModelProvider(clientBuilder.build());
    }

    /**
     * Builds through the SDK's own {@code fromEnv()}, with this builder's explicit {@code apiKey} /
     * {@code baseUrl} / {@code organization} (if set) layered on top afterward so they win over
     * whatever the environment supplied.
     *
     * <p>Unlike the Anthropic SDK (which defers a missing-credential failure to the first real
     * request), this SDK's own {@code ClientOptions.Builder.build()} already fails fast: it
     * resolves an {@code effectiveCredential()} synchronously and throws {@code
     * IllegalStateException} immediately when no credential source (API key, workload identity, or
     * admin key) is configured. {@value #API_KEY_ENV_VAR} is still checked directly here, ahead of
     * calling the SDK, so that common "nothing is configured" case produces our own friendly,
     * consistently shaped message naming the variable and the {@code apiKey(...)}/{@code
     * client(...)} alternatives — the same shape as the {@code apiKey}-only path above — rather
     * than the SDK's generic credential-source message. Any other failure the SDK does raise while
     * resolving (e.g. both {@value #API_KEY_ENV_VAR} and {@code AZURE_OPENAI_KEY} set at once) is
     * caught and rethrown in that same friendly shape.
     */
    private OpenAIClient buildFromEnv() {
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
  }
}
