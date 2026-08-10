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
package org.jwcarman.nessy.api.compaction;

import org.jwcarman.nessy.api.session.SessionState;

/**
 * Decides when a settled conversation should be summarized to keep it inside the model's context
 * window.
 *
 * <p>Pure and stateless — no clocks, no I/O — so a trigger reads the same at replay time as it did
 * live. Consulted by the reducer at every point it is about to ask the model to continue.
 */
public interface CompactionTrigger {

  /**
   * The singleton {@link #never()} instance. A dedicated constant (rather than a fresh lambda
   * returned on every call) gives {@link #never()} a stable identity that {@link
   * CompactionPolicy#disabled()} depends on being able to recognize — for instance, so {@code
   * AgentBuilder} can tell a policy that never compacts apart from one that merely triggers rarely,
   * without a fragile heuristic.
   */
  CompactionTrigger NEVER = state -> false;

  /** Whether the settled conversation should be compacted before the next model call. */
  boolean shouldCompact(SessionState state);

  /** Fires once {@link SessionState#lastInputTokens()} reaches {@code trigger}. */
  static CompactionTrigger atTokens(long trigger) {
    if (trigger < 1) {
      throw new IllegalArgumentException("trigger must be at least 1");
    }
    return state -> state.lastInputTokens() >= trigger;
  }

  /**
   * Derives a token trigger from a declared context window: fires at 80% of the room left over
   * after reserving {@code maxTokens} for the model's reply, so the summarization call itself still
   * fits.
   */
  static CompactionTrigger forWindow(long window, long maxTokens) {
    if (window <= maxTokens) {
      throw new IllegalArgumentException("window must be greater than maxTokens");
    }
    return atTokens(Math.max(1, (long) (0.8 * (window - maxTokens))));
  }

  /** Never fires: compaction effectively off. */
  static CompactionTrigger never() {
    return NEVER;
  }
}
