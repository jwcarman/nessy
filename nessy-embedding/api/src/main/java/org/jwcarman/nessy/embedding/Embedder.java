package org.jwcarman.nessy.embedding;

import java.util.List;
import java.util.Objects;

/**
 * Text into a vector, so that texts can be compared by meaning rather than by words.
 *
 * <p>Its own seam, apart from inference, on purpose. Not every inference vendor embeds, and the
 * embedding model is a property of the store that holds the vectors rather than of the agent that
 * talks: every vector in a table must come from one model, or the distances between them mean
 * nothing. So a store takes an {@code Embedder} at construction and records {@link #model()} beside
 * every vector; a harness never sees one.
 *
 * <p>Used off the model-call path: when a note or an episode is written, and when a query is made.
 */
public interface Embedder {

  /**
   * The vendor, as OpenTelemetry's GenAI semantic conventions name it for {@code
   * gen_ai.provider.name}: {@code openai}, {@code gcp.gemini}, {@code aws.bedrock}. Every adapter
   * says so; anything else is named for the class that wrote it.
   */
  default String providerName() {
    return nameOf(getClass());
  }

  /** The model's name, recorded beside every vector it produces. */
  String model();

  /** How many coordinates every vector from this model has. */
  int dimension();

  /**
   * Several texts' embeddings, in the order given, and the one an adapter implements.
   *
   * <p>The batch is the real call because that is the shape every vendor's endpoint takes: texts
   * in, vectors out, one request. Most batches here are of one -- a note, a query -- so this is not
   * about speed today; it is about there being one path to the vendor rather than two, and about
   * the day a caller does hand over a hundred notes not being the day it becomes a hundred round
   * trips.
   */
  List<Embedding> embed(List<String> texts);

  /**
   * One text's embedding: the degenerate batch, and never worth an adapter writing out.
   *
   * <p>Left overridable for the one implementation that needs to say something about a single call
   * rather than a batch of one -- {@link ObservedEmbedder} opens a span here.
   */
  default Embedding embed(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return embed(List.of(text)).getFirst();
  }

  /**
   * A class's simple name, or for an anonymous class the name of the class that wrote it. The name
   * becomes a metric tag, so it has to be stable, and an anonymous class has none of its own.
   *
   * <p>No lambda case, unlike the provider's: an embedder says its model and its dimension as well
   * as embedding, so it is never written as one.
   */
  private static String nameOf(Class<?> type) {
    return type.isAnonymousClass() && type.getEnclosingClass() != null
        ? type.getEnclosingClass().getSimpleName()
        : type.getSimpleName();
  }
}
