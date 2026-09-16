package org.jwcarman.nessy.embedding;

import java.util.ArrayList;
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

  /** The model's name, recorded beside every vector it produces. */
  String model();

  /** How many coordinates every vector from this model has. */
  int dimension();

  /** One text's embedding. */
  Embedding embed(String text);

  /**
   * Several texts' embeddings, in the order given. One call to the vendor where the vendor allows
   * it; the default asks one at a time, which is correct and slow.
   */
  default List<Embedding> embed(List<String> texts) {
    Objects.requireNonNull(texts, "texts must not be null");
    List<Embedding> embeddings = new ArrayList<>(texts.size());
    for (String text : texts) {
      embeddings.add(embed(text));
    }
    return List.copyOf(embeddings);
  }
}
