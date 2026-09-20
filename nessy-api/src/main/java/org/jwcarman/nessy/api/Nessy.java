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

import java.util.Optional;

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
   * Text into vectors, when one was configured.
   *
   * <p>Optional because embedding is a choice an application makes: memory can rank by relevance
   * when there is an embedder and by recency when there is not, and neither is a failure.
   */
  Optional<Embedder> embedder();

  /** Lets go of the pool, the schedule and the threads. */
  @Override
  void close();
}
