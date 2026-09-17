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

  /**
   * A class's simple name, or for a lambda or an anonymous class the name of the class that wrote
   * it. The name becomes a metric tag, so it has to be stable: a lambda's own name carries an
   * address that differs between runs, and an anonymous class has none at all.
   */
  private static String nameOf(Class<?> type) {
    if (type.isAnonymousClass() && type.getEnclosingClass() != null) {
      return type.getEnclosingClass().getSimpleName();
    }
    String name = type.getName();
    int lambda = name.indexOf("$$Lambda");
    if (lambda >= 0) {
      String writer = name.substring(0, lambda);
      return writer.substring(Math.max(writer.lastIndexOf('.'), writer.lastIndexOf('$')) + 1);
    }
    return type.getSimpleName();
  }
}
