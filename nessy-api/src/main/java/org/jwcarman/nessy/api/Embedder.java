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
package org.jwcarman.nessy.api;

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
   * The documents, in the order given.
   *
   * <p>One of the two things a retrieval system ever embeds, and it is not the same thing as the
   * other. A document is a statement waiting to be found; a query is the asking. Vendors that train
   * for retrieval place the two differently on purpose -- Gemini calls it a task type, Voyage an
   * input type -- and a store that embedded its summaries as though they were questions would rank
   * worse for no visible reason.
   *
   * <p>A batch because documents arrive in batches: a store writes what it has.
   */
  List<Embedding> embedDocuments(List<String> texts);

  /** One document, which is the degenerate batch. */
  default Embedding embedDocument(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return embedDocuments(List.of(text)).getFirst();
  }

  /**
   * The question being asked.
   *
   * <p>Singular, because it is: a search has one query, however many documents it is searching.
   *
   * <p>Defaulted to the other, because for most vendors that is the truth rather than a shortcut: a
   * model not trained to place questions and statements differently gains nothing from being told
   * which it was given, and an override saying so would be a copy of this line. Gemini and Voyage
   * do have something to say, and say it.
   */
  default Embedding embedQuery(String query) {
    return embedDocument(query);
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
