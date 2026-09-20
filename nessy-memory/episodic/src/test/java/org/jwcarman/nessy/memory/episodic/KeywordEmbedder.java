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
package org.jwcarman.nessy.memory.episodic;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.Embedder;
import org.jwcarman.nessy.api.Embedding;

/**
 * An embedder whose vectors can be reasoned about: one coordinate per keyword, counting mentions,
 * plus a small constant so no text is the zero vector. Two texts about the same keyword are close;
 * two about different ones are not.
 */
final class KeywordEmbedder implements Embedder {

  private final String model;
  private final List<String> keywords;
  final List<String> embedded = new CopyOnWriteArrayList<>();
  final List<String> asked = new CopyOnWriteArrayList<>();

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

  /** Which flavour a caller asked for, so a test can say a store got it right. */
  @Override
  public Embedding embedQuery(String query) {
    asked.add(query);
    return one(query);
  }

  @Override
  public List<Embedding> embedDocuments(List<String> texts) {
    embedded.addAll(texts);
    return texts.stream().map(this::one).toList();
  }

  private Embedding one(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    float[] vector = new float[keywords.size() + 1];
    for (int i = 0; i < keywords.size(); i++) {
      vector[i] = lower.split(keywords.get(i), -1).length - 1;
    }
    vector[keywords.size()] = 0.01f;
    return new Embedding(model, vector);
  }
}
