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
package org.jwcarman.nessy.api.tool.authorization;

import java.util.Objects;
import java.util.Optional;

/**
 * The impure gathering stage: deposits assessments into the context before the {@code UsagePolicy}
 * judges (design of record 2026-08-16-authorization §4). A grant wires these as an ordered list;
 * each receives the previous enricher's own context and the tool's rendered effect, and returns the
 * next context — {@link AuthzContext#with} functionally, so nothing upstream ever sees a later
 * enricher's deposit.
 *
 * <p>Enrichers MAY do I/O — a principal exchange, a risk service call, a quota read — the policy
 * stays pure so all of that impurity belongs here instead.
 *
 * <p><strong>Variance is the reuse story:</strong> a grant accepts {@code Enricher<? super E>}, so
 * an effect-blind decorator written once as {@code Enricher<Object>} (quota, tier, principal
 * exchange) composes into any grant, while an effect-aware assessor types itself to its own {@code
 * E} and the compiler welds it only to grants whose tool renders that same effect.
 *
 * <p>A throwing enricher fails the whole call closed: the chokepoint turns it into a {@code Deny}
 * naming the enricher stage, never lets the exception escape into the conversation loop, and never
 * lets a broken enricher become an allow.
 *
 * @param <E> the effect type this enricher reads
 */
@FunctionalInterface
public interface Enricher<E> {

  /** Returns the next context — {@code context} functionally extended, never mutated. */
  AuthzContext enrich(AuthzContext context, E effect);

  /**
   * A human-readable label for this enricher, read by {@link AuthorizationReport} (design §8) —
   * never by {@link #enrich} itself, and never consulted by the chokepoint: behavior never depends
   * on it. Empty by default, since a bare lambda has no name worth reporting (its {@code
   * getClass()} is a synthetic, unreadable token); name one with {@link #named(String, Enricher)}.
   */
  default Optional<String> displayName() {
    return Optional.empty();
  }

  /**
   * Wraps {@code delegate} so it reports {@code displayName} to {@link AuthorizationReport} —
   * decoration only, {@link #enrich} still delegates through unchanged.
   */
  static <E> Enricher<E> named(String displayName, Enricher<E> delegate) {
    Objects.requireNonNull(displayName, "displayName must not be null");
    if (displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    Objects.requireNonNull(delegate, "delegate must not be null");
    return new Enricher<>() {
      @Override
      public AuthzContext enrich(AuthzContext context, E effect) {
        return delegate.enrich(context, effect);
      }

      @Override
      public Optional<String> displayName() {
        return Optional.of(displayName);
      }
    };
  }
}
