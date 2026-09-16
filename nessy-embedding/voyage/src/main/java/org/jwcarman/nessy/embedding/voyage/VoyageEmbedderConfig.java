package org.jwcarman.nessy.embedding.voyage;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link VoyageEmbedder#create(VoyageEmbedderCustomizer)} hands a customizer: a CONFIG, not a
 * builder -- fluent setters, no public {@code build()}.
 */
public final class VoyageEmbedderConfig {

  /** Voyage's current general model: 1024 dimensions unless asked for 256, 512 or 2048. */
  public static final String DEFAULT_MODEL = "voyage-3.5";

  public static final String DEFAULT_BASE_URL = "https://api.voyageai.com/v1";

  private static final String API_KEY_ENV_VAR = "VOYAGE_API_KEY";

  private String apiKey;
  private String baseUrl = DEFAULT_BASE_URL;
  private String model = DEFAULT_MODEL;
  private OptionalInt dimension = OptionalInt.empty();
  private String inputType;
  private Duration timeout = Duration.ofSeconds(30);
  private HttpClient http;
  private JsonMapper mapper = JsonMapper.builder().build();
  private boolean useEnv;

  VoyageEmbedderConfig() {}

  public VoyageEmbedderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /** {@value #API_KEY_ENV_VAR}; an explicit key wins. */
  public VoyageEmbedderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** The API root, {@value #DEFAULT_BASE_URL} by default; {@code /embeddings} is appended. */
  public VoyageEmbedderConfig baseUrl(String baseUrl) {
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    return this;
  }

  public VoyageEmbedderConfig model(String model) {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    this.model = model;
    return this;
  }

  /** Ask for this many coordinates; the 3.5 models honour 256, 512, 1024 and 2048. */
  public VoyageEmbedderConfig dimension(int dimension) {
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be positive: " + dimension);
    }
    this.dimension = OptionalInt.of(dimension);
    return this;
  }

  /** Voyage's hint: {@code document} or {@code query}. None by default. */
  public VoyageEmbedderConfig inputType(String inputType) {
    this.inputType = inputType;
    return this;
  }

  public VoyageEmbedderConfig timeout(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    return this;
  }

  /** The JDK client to send with; one is built if none is given. Never closed by the embedder. */
  public VoyageEmbedderConfig httpClient(HttpClient http) {
    this.http = Objects.requireNonNull(http, "http must not be null");
    return this;
  }

  public VoyageEmbedderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  VoyageEmbedder build() {
    String key = apiKey;
    if (useEnv && key == null) {
      key = System.getenv(API_KEY_ENV_VAR);
      if (key == null) {
        throw new IllegalStateException(
            API_KEY_ENV_VAR + " environment variable is not set; call apiKey(...) instead");
      }
    }
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("an API key is required: call apiKey(...) or fromEnv()");
    }
    String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return new VoyageEmbedder(
        http != null ? http : HttpClient.newBuilder().connectTimeout(timeout).build(),
        URI.create(root + "/embeddings"),
        key,
        this);
  }

  String model() {
    return model;
  }

  OptionalInt dimension() {
    return dimension;
  }

  String inputType() {
    return inputType;
  }

  Duration timeout() {
    return timeout;
  }

  JsonMapper mapper() {
    return mapper;
  }
}
