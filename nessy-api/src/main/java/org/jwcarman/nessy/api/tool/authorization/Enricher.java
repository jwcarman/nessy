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
 * judges (design of record 2026-08-16-authorization §4, amended by action-wave spec §1). A grant
 * wires these as an ordered list; each receives the previous enricher's own context and returns the
 * next context — {@link AuthzContext#with} functionally, so nothing upstream ever sees a later
 * enricher's deposit.
 *
 * <p>Enrichers MAY do I/O — a principal exchange, a risk service call, a quota read — the policy
 * stays pure so all of that impurity belongs here instead.
 *
 * <p>The pipeline is monomorphic (action-wave spec §8): no type parameter here or on {@link
 * org.jwcarman.nessy.api.tool.UsagePolicy}. The action travels only as {@link
 * AuthzContext#ACTION_KEY}, deposited before any enricher runs; an action-aware enricher recovers
 * it with {@link AuthzContext#action(Class)} and fails closed on its own terms if the slot is empty
 * or mistyped.
 *
 * <p>A throwing enricher fails the whole call closed: the chokepoint turns it into a {@code Deny}
 * naming the enricher stage, never lets the exception escape into the conversation loop, and never
 * lets a broken enricher become an allow.
 */
@FunctionalInterface
public interface Enricher {

  /** Returns the next context — {@code context} functionally extended, never mutated. */
  AuthzContext enrich(AuthzContext context);

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
  static Enricher named(String displayName, Enricher delegate) {
    Objects.requireNonNull(displayName, "displayName must not be null");
    if (displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    Objects.requireNonNull(delegate, "delegate must not be null");
    return new Enricher() {
      @Override
      public AuthzContext enrich(AuthzContext context) {
        return delegate.enrich(context);
      }

      @Override
      public Optional<String> displayName() {
        return Optional.of(displayName);
      }
    };
  }
}
