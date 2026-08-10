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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionState;
import org.jwcarman.nessy.api.session.Usage;

/**
 * Decides when the settled conversation needs shrinking, and shrinks it.
 *
 * <p>Split into a pure half and an effectful half for the same reason the rest of this codebase
 * separates deciding from doing: {@link #requiresCompaction} costs nothing and is safe for the
 * reducer to consult on every {@code CallModel} decision point, while {@link #compact} may call a
 * model, spend money, or take real time, so only the engine ever performs it. The reducer never
 * computes what to keep versus summarize; that choice belongs entirely to the strategy.
 *
 * <p><b>Where the default lives:</b> the natural implementation — summarize the head of the working
 * set through a summarizer and keep the tail verbatim — is <em>not</em> a static factory on this
 * interface, even though the design this type traces back to sketches one here. {@code api} may
 * never depend on {@code spi} (see {@code ZoneBoundariesTest}), and that default needs a {@code
 * Summarizer}, which is an {@code spi.compaction} type. So the summarizing default is {@code
 * org.jwcarman.nessy.spi.compaction.CompactionStrategies#summarizing(CompactionPolicy, Summarizer)}
 * instead; {@link #disabled()} is the only factory that belongs here, because it needs nothing from
 * {@code spi}. Most callers reach the summarizing default through {@code AgentBuilder}, which
 * assembles it for you.
 */
public interface CompactionStrategy {

  /** Pure — the reducer consults this at every {@code CallModel} decision point. */
  boolean requiresCompaction(SessionState state);

  /**
   * Effectful — the ENGINE performs this, never the reducer. Returns a smaller working set and what
   * producing it cost.
   */
  Result compact(List<Message> workingSet);

  /**
   * The outcome of one compaction attempt.
   *
   * @param workingSet the messages the reducer should replace {@code state.messages()} with. A
   *     result no smaller than what went in is treated as a skip, not a shrink.
   * @param spend what producing {@code workingSet} cost; {@link Usage#zero()} for a strategy that
   *     never calls a model.
   */
  record Result(List<Message> workingSet, Usage spend) {

    public Result {
      Objects.requireNonNull(workingSet, "workingSet must not be null");
      workingSet = List.copyOf(workingSet);
      Objects.requireNonNull(spend, "spend must not be null");
    }
  }

  /**
   * Never compacts: {@link #requiresCompaction} always answers {@code false}, so the reducer never
   * emits {@code Effect.Compact} for this strategy and {@link #compact} is never reachable in
   * practice. It still throws rather than silently doing nothing, in case a caller invokes it
   * directly.
   */
  static CompactionStrategy disabled() {
    return new CompactionStrategy() {
      @Override
      public boolean requiresCompaction(SessionState state) {
        return false;
      }

      @Override
      public Result compact(List<Message> workingSet) {
        throw new IllegalStateException(
            "compaction is disabled: requiresCompaction() always returns false, so the reducer"
                + " should never have emitted Effect.Compact for this strategy");
      }
    };
  }
}
