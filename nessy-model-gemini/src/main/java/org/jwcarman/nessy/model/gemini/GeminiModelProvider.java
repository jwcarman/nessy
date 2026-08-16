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
import com.google.genai.types.HttpOptions;
import java.util.Set;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The public face of the Gemini provider module: turns a {@link ModelRequest} into a live streaming
 * call against Google's java-genai SDK, talking to the Gemini Developer API via a plain API key
 * (Vertex AI's project/location/credentials auth is out of scope for v1).
 *
 * <p>Everything upstream of this class is pure translation ({@link GeminiRequests}, {@link
 * GeminiStream}); this class is the one place that owns a {@link GeminiClient} and actually talks
 * to the network.
 *
 * <p>{@link Capability#THINKING} is deliberately absent: Gemini's {@code thought}-flagged parts are
 * dropped by {@link GeminiStream} rather than translated, and wiring them up requires both a
 * thought-part-to-{@code ThinkingChunk} mapping and a capabilities flag — banked, not done, per the
 * provider-expansion design (§2, §6). {@link Capability#PROMPT_CACHING}, {@link
 * Capability#PARALLEL_TOOL_CALLS}, and {@link Capability#IMAGE_INPUT} are equally unadvertised:
 * none of the request/response mapping in this module wires them up yet, so none is claimed — this
 * provider's v1 surface is exactly what the design calls for: text, tool calls, and honest usage.
 */
public final class GeminiModelProvider implements ModelProvider {

  private static final Set<Capability> CAPABILITIES = Set.of();

  private final GeminiClient client;

  GeminiModelProvider(GeminiClient client) {
    this.client = client;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    var contents = GeminiRequests.toContents(request);
    var config = GeminiRequests.toConfig(request);
    return client.generateContentStream(request.model(), contents, config);
  }

  @Override
  public Set<Capability> capabilities() {
    return CAPABILITIES;
  }

  /** Assembles a {@link GeminiModelProvider}. */
  public static final class Builder {

    private static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
    private static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";

    private String apiKey;
    private String baseUrl;
    private Client client;
    private boolean useEnv;

    private Builder() {}

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Reads {@value #GEMINI_API_KEY_ENV_VAR} then, if that is unset, {@value
     * #GOOGLE_API_KEY_ENV_VAR} — Google's own documented pair, in that order — itself, rather than
     * delegating to the SDK's own environment resolution the way {@code new Client()} would. This
     * is the seam-integrity rule the env module established: the choice this builder makes from the
     * environment is the choice that gets built, not a second, independent read underneath it.
     *
     * <p>Only a flag is set here; nothing is read yet. {@link #build()} applies it, layering any
     * explicit {@link #apiKey(String)} set on <em>this</em> builder on top so it wins over either
     * environment variable.
     *
     * @throws IllegalStateException at {@link #build()} time if neither an explicit key nor either
     *     environment variable is available
     */
    public Builder fromEnv() {
      this.useEnv = true;
      return this;
    }

    /**
     * Overrides the API base URL — for proxies, gateways, or Gemini-compatible endpoints. Applied
     * via the SDK's {@link HttpOptions#baseUrl()}.
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Escape hatch: supply a fully preconfigured SDK client instead of {@code apiKey}/{@code
     * baseUrl}.
     */
    public Builder client(Client client) {
      this.client = client;
      return this;
    }

    public GeminiModelProvider build() {
      return new GeminiModelProvider(resolveClient());
    }

    private GeminiClient resolveClient() {
      if (client != null) {
        return wrap(client);
      }
      if (useEnv) {
        return wrap(buildFromEnv());
      }
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalStateException(
            "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
                + " client via client(...)");
      }
      return wrap(buildClient(apiKey));
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

    private static GeminiClient wrap(Client sdkClient) {
      return (model, contents, config) -> {
        var responseStream = sdkClient.models.generateContentStream(model, contents, config);
        return new GeminiStream(responseStream, responseStream::close);
      };
    }
  }
}
