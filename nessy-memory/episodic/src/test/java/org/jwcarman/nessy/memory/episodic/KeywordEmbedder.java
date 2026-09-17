package org.jwcarman.nessy.memory.episodic;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.embedding.Embedder;
import org.jwcarman.nessy.embedding.Embedding;

/**
 * An embedder whose vectors can be reasoned about: one coordinate per keyword, counting mentions,
 * plus a small constant so no text is the zero vector. Two texts about the same keyword are close;
 * two about different ones are not.
 */
final class KeywordEmbedder implements Embedder {

  private final String model;
  private final List<String> keywords;
  final List<String> embedded = new CopyOnWriteArrayList<>();

  KeywordEmbedder(String model, String... keywords) {
    this.model = model;
    this.keywords = List.of(keywords);
  }

  @Override
  public String providerName() {
    return "keywords";
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public int dimension() {
    return keywords.size() + 1;
  }

  @Override
  public Embedding embed(String text) {
    embedded.add(text);
    String lower = text.toLowerCase(Locale.ROOT);
    float[] vector = new float[keywords.size() + 1];
    for (int i = 0; i < keywords.size(); i++) {
      vector[i] = lower.split(keywords.get(i), -1).length - 1;
    }
    vector[keywords.size()] = 0.01f;
    return new Embedding(model, vector);
  }
}
