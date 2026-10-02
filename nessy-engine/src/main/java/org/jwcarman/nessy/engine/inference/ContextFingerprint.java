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
package org.jwcarman.nessy.engine.inference;

import java.util.Objects;
import org.jwcarman.nessy.inference.InferenceContext;

/**
 * One hash per stratum of an {@link InferenceContext}, so that two contexts can be compared for
 * where they first differ.
 *
 * <p>A provider caches the leading text of a request, and the context is laid out
 * most-stable-first: history, memory, state, the active turn, ambient. What survives of a cache is
 * everything before the earliest change, which makes that one change the figure that matters;
 * anything after it was going to be re-read regardless.
 *
 * <p>Each hash is the {@code hashCode} of the stratum's own records, so what counts as a change is
 * what the records consider different. Summaries and tail together are the history: both are
 * written down once and re-sent verbatim.
 *
 * @param history the summaries and then the tail
 * @param memory what was recalled for the turn
 * @param state the agent's standing situation
 * @param activeTurn the turn being answered
 * @param ambient what can change while the agent works
 */
public record ContextFingerprint(int history, int memory, int state, int activeTurn, int ambient) {

  /** The fingerprint of {@code context}, stratum by stratum. */
  public static ContextFingerprint of(InferenceContext context) {
    Objects.requireNonNull(context, "context must not be null");
    return new ContextFingerprint(
        Objects.hash(context.summaries(), context.tail()),
        context.memory().hashCode(),
        context.state().hashCode(),
        context.activeTurn().hashCode(),
        context.ambient().hashCode());
  }

  /**
   * The earliest stratum that differs from {@code previous}: {@code "none"}, {@code "history"},
   * {@code "memory"}, {@code "state"}, {@code "active-turn"} or {@code "ambient"}.
   */
  public String firstChangeSince(ContextFingerprint previous) {
    Objects.requireNonNull(previous, "previous must not be null");
    if (history != previous.history) {
      return "history";
    }
    if (memory != previous.memory) {
      return "memory";
    }
    if (state != previous.state) {
      return "state";
    }
    if (activeTurn != previous.activeTurn) {
      return "active-turn";
    }
    if (ambient != previous.ambient) {
      return "ambient";
    }
    return "none";
  }
}
