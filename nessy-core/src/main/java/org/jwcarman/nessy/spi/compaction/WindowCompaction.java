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
package org.jwcarman.nessy.spi.compaction;

import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.message.Context;

/**
 * The zero-spend, lossy {@link Compactor}: drops the working set's head at the nearest pair-safe
 * boundary that still leaves {@code keepRecent} messages verbatim, and keeps nothing in its place —
 * no summary, no model call. Pure over its input: {@link #compact} never calls a model or spends a
 * cent, unlike {@link SummarizingCompaction}, which trades tokens for fidelity.
 *
 * <p>Package-private: reached only through {@link Compactors#window}, never constructed directly.
 */
record WindowCompaction(long triggerTokens, int keepRecent) implements Compactor {

  // WindowBuilder is the only construction path (see the class javadoc) and already validates
  // both of these before build() ever calls this constructor; the checks stay here anyway as
  // this record's own house-validation guard, independent of its one caller.
  WindowCompaction {
    if (triggerTokens < 1) {
      throw new IllegalArgumentException("triggerTokens must be at least 1");
    }
    if (keepRecent < 0) {
      throw new IllegalArgumentException("keepRecent must be at least 0");
    }
  }

  @Override
  public boolean requiresCompaction(ConversationState state) {
    return state.lastInputTokens() >= triggerTokens;
  }

  /**
   * No spend, no summary: {@link Context#keepRecent(int)} does the whole job. When no pair-safe
   * boundary exists short of the whole context, {@code keepRecent} returns the working set
   * unchanged — the reducer's non-shrinking-result rule treats that the same as any other skip.
   */
  @Override
  public Result compact(ConversationState state) {
    return new Result(Context.of(state.messages()).keepRecent(keepRecent).messages());
  }
}
