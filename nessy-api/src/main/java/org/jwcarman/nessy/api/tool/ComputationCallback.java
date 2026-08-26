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
package org.jwcarman.nessy.api.tool;

import java.time.Instant;

/**
 * What to do once the computation exists (deferral-by-callback spec §1) — returned by the deferring
 * party instead of it calling a {@code defer()} that folds from inside an effect. The plumbing
 * creates the computation, folds the wait, commits, and only then runs this: the id cannot exist
 * before the fold, so "returned a deferral without parking" and "answered after deferring" both
 * stop being writable.
 *
 * <p>A <b>callback</b>, never a "continuation" (spec §1): Continuum already owns that word for a
 * computation's durable return address, and the two point opposite ways — Continuum's says where
 * the answer comes back to, this one says how the world is told where to send it.
 *
 * <p>It carries a closure, so it lives on an {@code Effect} and is never written to state: a crash
 * before it runs loses it, and recovery re-fires the originating step rather than resuming (spec
 * §4).
 */
@FunctionalInterface
public interface ComputationCallback {

  /**
   * Tells the world where to answer.
   *
   * @param id the computation the answer must ride
   * @param deadline what was actually agreed — the term, clipped to the harness's ceiling (spec
   *     §5), so a party that asked for a year and got seven days cannot promise a human something
   *     false
   */
  void accept(ComputationId id, Instant deadline);
}
