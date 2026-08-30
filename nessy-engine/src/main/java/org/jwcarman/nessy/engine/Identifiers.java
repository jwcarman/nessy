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
package org.jwcarman.nessy.engine;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

/**
 * Ids the engine mints for itself: backlog entries, turns, events.
 *
 * <p>UUIDv7, so they are time-ordered as well as unique. That is what lets an event id double as an
 * SSE cursor and a turn's duration be the distance between two ids, with no separate sequence.
 *
 * <p><b>ONE generator, shared.</b> Ordering within a millisecond comes from a counter the generator
 * keeps, so a fresh generator per call produces ids that are unique but NOT sorted — two events in
 * the same millisecond come back in random order, and a reconnecting subscriber replaying
 * "everything after this id" would then drop or repeat them. Minting from one instance is what
 * makes the ordering real rather than approximate.
 */
final class Identifiers {

  private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

  private Identifiers() {}

  /**
   * Synchronised because the generator is shared across every actor on this node, and the counter
   * that makes ids monotonic is its own mutable state.
   */
  static synchronized String next() {
    return GENERATOR.generate().toString();
  }
}
