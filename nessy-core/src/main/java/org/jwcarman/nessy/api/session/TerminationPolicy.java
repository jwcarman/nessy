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
package org.jwcarman.nessy.api.session;

import java.util.List;
import java.util.Optional;

/**
 * Decides when the loop must stop calling the model.
 *
 * <p>Pure and stateless: consulted by the reducer, never by the engine, so termination is semantics
 * — identical on every engine. The reducer consults it after applying any event that could lead to
 * another model call; a halt settles pending work (answering every outstanding tool_use, preserving
 * the transcript invariant), records the reason, and fails the session with no effects.
 */
public interface TerminationPolicy {

  /** A human-readable reason to halt, or empty to continue. */
  Optional<String> shouldHalt(SessionState state);

  static TerminationPolicy maxTurns(int max) {
    requireAtLeastOne(max, "maxTurns");
    return state ->
        state.turns() >= max
            ? Optional.of("reached the turn ceiling (" + max + " turns)")
            : Optional.empty();
  }

  static TerminationPolicy maxConsecutiveErrors(int max) {
    requireAtLeastOne(max, "maxConsecutiveErrors");
    return state ->
        state.consecutiveErrors() >= max
            ? Optional.of("hit the error ceiling (" + max + " consecutive tool errors)")
            : Optional.empty();
  }

  static TerminationPolicy anyOf(TerminationPolicy... policies) {
    List<TerminationPolicy> all = List.of(policies);
    return state ->
        all.stream().map(p -> p.shouldHalt(state)).flatMap(Optional::stream).findFirst();
  }

  static TerminationPolicy never() {
    return state -> Optional.empty();
  }

  /** The wallet-guarding default: three consecutive errors or one hundred turns. */
  static TerminationPolicy defaults() {
    return anyOf(maxConsecutiveErrors(3), maxTurns(100));
  }

  private static void requireAtLeastOne(int max, String name) {
    if (max < 1) {
      throw new IllegalArgumentException(name + " must be at least 1");
    }
  }
}
