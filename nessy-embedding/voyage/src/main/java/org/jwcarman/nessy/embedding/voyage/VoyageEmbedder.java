package org.jwcarman.nessy.embedding.voyage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import org.jwcarman.nessy.embedding.Embedder;
import org.jwcarman.nessy.embedding.Embedding;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Voyage AI's embeddings, over plain HTTP: the embedding partner Anthropic points to, since
 * Anthropic has no embeddings of its own. No SDK, so the client is the JDK's.
 *
 * <p>One embedder is one model at one dimension, decided where it is built.
 */
public final class VoyageEmbedder implements Embedder, AutoCloseable {

  private static final int BATCH = 128;

  private final HttpClient http;
  private final URI endpoint;
  private final String apiKey;
  private final String model;
  private final OptionalInt requestedDimension;
  private final String inputType;
  private final Duration timeout;
  private final JsonMapper mapper;
  private volatile int dimension;

  /** The client, endpoint and key are what the config resolved; the rest is read as configured. */
  VoyageEmbedder(HttpClient http, URI endpoint, String apiKey, VoyageEmbedderConfig config) {
    this.http = Objects.requireNonNull(http, "http must not be null");
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
    Objects.requireNonNull(config, "config must not be null");
    this.model = Objects.requireNonNull(config.model(), "model must not be null");
    this.requestedDimension = Objects.requireNonNull(config.dimension(), "dimension");
    this.inputType = config.inputType();
    this.timeout = Objects.requireNonNull(config.timeout(), "timeout must not be null");
    this.mapper = Objects.requireNonNull(config.mapper(), "mapper must not be null");
    this.dimension = requestedDimension.orElse(0);
  }

  public static VoyageEmbedder create(VoyageEmbedderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    VoyageEmbedderConfig config = new VoyageEmbedderConfig();
    customizer.customize(config);
    return config.build();
  }

  /** {@code VOYAGE_API_KEY} and the default model. */
  public static VoyageEmbedder fromEnv() {
    return create(VoyageEmbedderConfig::fromEnv);
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public Embedding embed(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return embed(List.of(text)).getFirst();
  }

  /** Batches of up to {@value #BATCH}, each one request; the reply is indexed and put in order. */
  @Override
  public List<Embedding> embed(List<String> texts) {
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    Embedding[] ordered = new Embedding[texts.size()];
    for (int from = 0; from < texts.size(); from += BATCH) {
      List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
      JsonNode reply = post(batch);
      for (JsonNode item : reply.path("data")) {
        int index = item.path("index").asInt(-1);
        if (index < 0 || index >= batch.size()) {
          throw new IllegalStateException("the vendor returned an embedding for index " + index);
        }
        ordered[from + index] = vector(item.path("embedding"));
      }
    }
    for (Embedding embedding : ordered) {
      if (embedding == null) {
        throw new IllegalStateException("the vendor returned fewer embeddings than texts");
      }
    }
    if (dimension == 0) {
      dimension = ordered[0].dimension();
    }
    return List.of(ordered);
  }

  private JsonNode post(List<String> batch) {
    ObjectNode body = mapper.createObjectNode().put("model", model);
    ArrayNode input = body.putArray("input");
    batch.forEach(input::add);
    requestedDimension.ifPresent(d -> body.put("output_dimension", d));
    if (inputType != null) {
      body.put("input_type", inputType);
    }
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted asking " + endpoint, e);
    } catch (IOException e) {
      throw new IllegalStateException("could not reach " + endpoint, e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "Voyage answered " + response.statusCode() + ": " + response.body());
    }
    return mapper.readTree(response.body());
  }

  private Embedding vector(JsonNode values) {
    if (!values.isArray() || values.isEmpty()) {
      throw new IllegalStateException("the vendor returned no embedding");
    }
    float[] vector = new float[values.size()];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) values.get(i).asDouble();
    }
    return new Embedding(model, vector);
  }

  /**
   * Nothing to release: the JDK client is shared and unowned. Here so a try-with-resources reads.
   */
  @Override
  public void close() {
    // The HttpClient is not this class's to close.
  }
}
