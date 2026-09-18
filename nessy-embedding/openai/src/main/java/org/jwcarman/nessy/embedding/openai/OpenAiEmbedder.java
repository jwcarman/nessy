package org.jwcarman.nessy.embedding.openai;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import org.jwcarman.nessy.embedding.Embedder;
import org.jwcarman.nessy.embedding.Embedding;

/**
 * OpenAI's embeddings endpoint, through the vendor's own SDK, and with a base URL every service
 * that speaks the same wire: a local runtime serving {@code nomic-embed-text} is this class with a
 * different URL.
 *
 * <p>One embedder is one model at one dimension, decided where it is built: a store keyed on this
 * embedder's vectors is keyed on that model, and a second model is a second embedder.
 */
public final class OpenAiEmbedder implements Embedder, AutoCloseable {

  private final OpenAIClient client;
  private final boolean ownsClient;
  private final String model;
  private final OptionalInt requestedDimension;
  private volatile int dimension;

  OpenAiEmbedder(
      OpenAIClient client, boolean ownsClient, String model, OptionalInt requestedDimension) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.ownsClient = ownsClient;
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.requestedDimension = Objects.requireNonNull(requestedDimension, "dimension");
    this.dimension = requestedDimension.orElse(0);
  }

  public static OpenAiEmbedder create(OpenAiEmbedderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    OpenAiEmbedderConfig config = new OpenAiEmbedderConfig();
    customizer.customize(config);
    return config.build();
  }

  /** {@code OPENAI_API_KEY} and the default model, {@value OpenAiEmbedderConfig#DEFAULT_MODEL}. */
  public static OpenAiEmbedder fromEnv() {
    return create(OpenAiEmbedderConfig::fromEnv);
  }

  /**
   * OpenAI, and anything that speaks its wire at another base URL: nothing more is known about it.
   */
  @Override
  public String providerName() {
    return "openai";
  }

  @Override
  public String model() {
    return model;
  }

  /**
   * The dimension asked for, or once the first vector has come back, the dimension the model
   * produces. Zero before either: a model's width is the vendor's fact, not this class's guess.
   */
  @Override
  public int dimension() {
    return dimension;
  }

  /**
   * One request for the whole batch; the vendor returns them indexed, and they are put in order.
   */
  @Override
  public List<Embedding> embed(List<String> texts) {
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    EmbeddingCreateParams.Builder params =
        EmbeddingCreateParams.builder().model(model).inputOfArrayOfStrings(texts);
    requestedDimension.ifPresent(params::dimensions);
    CreateEmbeddingResponse response = client.embeddings().create(params.build());

    Embedding[] ordered = new Embedding[texts.size()];
    for (com.openai.models.embeddings.Embedding item : response.data()) {
      List<Float> values = item.embedding();
      float[] vector = new float[values.size()];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = values.get(i);
      }
      ordered[(int) item.index()] = new Embedding(model, vector);
    }
    List<Embedding> embeddings = new ArrayList<>(ordered.length);
    for (Embedding embedding : ordered) {
      if (embedding == null) {
        throw new IllegalStateException("the vendor returned fewer embeddings than texts");
      }
      embeddings.add(embedding);
    }
    if (dimension == 0) {
      dimension = embeddings.getFirst().dimension();
    }
    return List.copyOf(embeddings);
  }

  /**
   * Closes the client this embedder BUILT; one handed in through the config is never closed here.
   */
  @Override
  public void close() {
    if (ownsClient) {
      client.close();
    }
  }
}
