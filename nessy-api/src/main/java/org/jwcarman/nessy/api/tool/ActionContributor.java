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

import java.util.Objects;
import java.util.Optional;

/**
 * Produces the action — the trusted statement of what one call will do — from the bound input
 * (action-wave spec §1). NOT an {@link org.jwcarman.nessy.api.tool.authorization.Enricher}: an
 * enricher consumes the action and deposits assessments; the contributor produces it. The grant
 * welds it at construction, so the application states the action even for third-party tools whose
 * own {@link Tool} implementation never speaks for itself.
 *
 * @param <I> the tool's own input type
 * @param <A> the action type this contributor renders
 */
@FunctionalInterface
public interface ActionContributor<I, A> {

  /**
   * Renders the action for one call, bound from {@code input}. Must not return {@code null}: the
   * grant sets the result as the request's {@code action} in the same action stage that calls this
   * method, and that setter itself refuses a {@code null} value — so a {@code null} action fails
   * the call closed there, naming the action stage, rather than surfacing later as an unnamed
   * {@code NullPointerException}.
   */
  A actionOf(I input);

  /**
   * A human-readable label for this contributor, read by {@code AuthorizationReport} — never by
   * {@link #actionOf} itself. Empty by default, since a bare lambda has no name worth reporting;
   * name one with {@link #named(String, ActionContributor)}.
   */
  default Optional<String> displayName() {
    return Optional.empty();
  }

  /**
   * Wraps {@code delegate} so it reports {@code displayName} to {@code AuthorizationReport} —
   * decoration only, {@link #actionOf} still delegates through unchanged.
   */
  static <I, A> ActionContributor<I, A> named(
      String displayName, ActionContributor<I, A> delegate) {
    Objects.requireNonNull(displayName, "displayName must not be null");
    if (displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    Objects.requireNonNull(delegate, "delegate must not be null");
    return new ActionContributor<>() {
      @Override
      public A actionOf(I input) {
        return delegate.actionOf(input);
      }

      @Override
      public Optional<String> displayName() {
        return Optional.of(displayName);
      }
    };
  }
}
