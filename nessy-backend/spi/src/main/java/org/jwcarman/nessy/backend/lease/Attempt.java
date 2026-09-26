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
package org.jwcarman.nessy.backend.lease;

/**
 * Whether {@link Leases#tryWithLease} ran the work, and what it produced.
 *
 * <p>Two arms rather than an empty optional, because "nobody ran it" and "it ran and produced
 * nothing" are different answers and a caller acts differently on each. An optional would report
 * side-effecting work -- the kind a lease most often guards -- as though it had been refused.
 */
public sealed interface Attempt<T> {

  /** It ran, and this is what it returned -- which may be null, if that is what the work says. */
  record Ran<T>(T result) implements Attempt<T> {}

  /**
   * Somebody else held the lease, so nothing happened.
   *
   * <p>Deliberately says nothing about who holds it or for how long: a lease's row says only what
   * it said a moment ago, and by the time a caller reads {@code Ignored} that may already be stale.
   */
  record Ignored<T>() implements Attempt<T> {}

  /** What the work produced, or {@code other} if it never ran. */
  default T orElse(T other) {
    return this instanceof Ran<T>(T result) ? result : other;
  }
}
