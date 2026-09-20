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

import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.extraction.ExtractorFactory;

/**
 * One set of model connections, and the ways an application uses them.
 *
 * <p>Three, and they are genuinely different work rather than three names for a call. A harness is
 * an agent: history, tools it may run, approvals, a turn that survives a restart. An extractor is
 * one call to a model that has been given nothing to act with, for reading an untrusted document
 * into a shape. An embedder turns text into a vector. What they share is the connection, the model
 * and how the calls are reported -- which is why they are handed out from one place rather than
 * each being wired again.
 *
 * <p><b>Hold one.</b> It owns a pool, a scheduler and the threads that perform effects; an
 * application has one the way it has one {@code DataSource}. In Spring that is a singleton and
 * nothing has to be said about it. Elsewhere it is built once and closed once.
 *
 * <p>Built by an implementation, because what it takes -- a database, a provider, a schema
 * generator -- is wiring rather than vocabulary. This is the door; {@code DefaultNessy.of(...)} is
 * where one comes from.
 */
public interface Nessy extends AutoCloseable {

  /**
   * Agents, by type.
   *
   * <p>The full weight of the thing: a harness folds observations into a story, writes down what it
   * owes, performs it, and can be picked up by another process after this one has gone.
   */
  HarnessFactory harnesses();

  /**
   * Readers of untrusted documents.
   *
   * <p>No agent, no database, no turn. A document is shown to a model that holds no tools and the
   * fields come back in a shape that was asked for -- claims rather than facts, which is the whole
   * of what this is for.
   */
  ExtractorFactory extractors();

  /**
   * Embedders, one per store.
   *
   * <p>Which model is the store's decision rather than this one's: a table of vectors is keyed on
   * the model that made them, so two stores need not agree and neither of them is "the
   * application's model".
   *
   * <p><b>Throws when nothing was wired.</b> Asking is saying you need one, and needing one that is
   * not there is a wiring mistake -- a missing {@code nessy-embedding} module, or a key that was
   * never set. Better said while an application is starting than found later, when retrieval has
   * quietly been ranking by recency.
   *
   * <p>A store that works either way should not ask. Episodic memory ranks by relevance when it was
   * given an embedder and by recency when it was not, and says so in its own configuration -- which
   * is where that choice belongs, because that is where it is known.
   *
   * @throws IllegalStateException when no embedding provider was configured
   */
  EmbedderFactory embedders();

  /** Lets go of the pool, the schedule and the threads. */
  @Override
  void close();
}
