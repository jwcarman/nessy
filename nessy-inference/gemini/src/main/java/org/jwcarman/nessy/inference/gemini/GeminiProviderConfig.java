package org.jwcarman.nessy.inference.gemini;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link GeminiInferenceProvider#create(GeminiProviderCustomizer)} hands a customizer: a
 * CONFIG, not a builder -- fluent setters, no public {@code build()}.
 */
public final class GeminiProviderConfig {

  private static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
  private static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";

  private String apiKey;
  private String baseUrl;
  private Client client;
  private boolean useEnv;
  private JsonMapper mapper = JsonMapper.builder().build();

  GeminiProviderConfig() {}

  public GeminiProviderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * Reads {@value #GEMINI_API_KEY_ENV_VAR} then, if that is unset, {@value
   * #GOOGLE_API_KEY_ENV_VAR}, Google's own documented pair in that order. Only a flag is set here;
   * {@link #build()} applies it, with an explicit {@link #apiKey(String)} winning over either
   * variable.
   */
  public GeminiProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** Overrides the API base URL, for proxies, gateways or Gemini-compatible endpoints. */
  public GeminiProviderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  /**
   * Escape hatch: a fully preconfigured SDK client instead of {@code apiKey}/{@code baseUrl}.
   *
   * <p><b>Ownership stays with the caller.</b> The provider closes only a client it built itself.
   */
  public GeminiProviderConfig client(Client client) {
    this.client = client;
    return this;
  }

  public GeminiProviderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  GeminiInferenceProvider build() {
    return new GeminiInferenceProvider(resolveClient(), mapper);
  }

  private GeminiClient resolveClient() {
    if (client != null) {
      return GeminiClient.over(client, false);
    }
    String key = apiKey;
    if (useEnv && key == null) {
      key = System.getenv(GEMINI_API_KEY_ENV_VAR);
      if (key == null) {
        key = System.getenv(GOOGLE_API_KEY_ENV_VAR);
      }
      if (key == null) {
        throw new IllegalStateException(
            GEMINI_API_KEY_ENV_VAR
                + " (or "
                + GOOGLE_API_KEY_ENV_VAR
                + ") environment variable is not set; call apiKey(...) or client(...) instead");
      }
    }
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
              + " client via client(...)");
    }
    Client.Builder builder = Client.builder().apiKey(key);
    if (baseUrl != null) {
      builder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
    }
    return GeminiClient.over(builder.build(), true);
  }
}
