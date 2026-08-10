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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionState;

/**
 * The default {@link Compactor}: cuts the working set at the last pair-safe boundary that still
 * leaves {@code keepRecent} messages verbatim, hands the head to a {@link Summarizer}, and splices
 * the summary back in front of the untouched tail.
 *
 * <p>Package-private: reached only through {@link Compactors#summarizing}, never constructed
 * directly.
 */
record SummarizingCompaction(Summarizer summarizer, long triggerTokens, int keepRecent)
    implements Compactor {

  /**
   * Moved here from {@code Reducer}: summary formatting is this compactor's business, not the
   * reducer's. Kept as the exact string the reducer used to own.
   */
  static final String SUMMARY_PREFIX = "[Conversation summary — earlier turns compacted]\n";

  // SummarizingBuilder is the only construction path (see the class javadoc) and already
  // validates every one of these before build() ever calls this constructor; the checks stay
  // here anyway as this record's own house-validation guard, independent of its one caller.
  SummarizingCompaction {
    Objects.requireNonNull(summarizer, "summarizer must not be null");
    if (triggerTokens < 1) {
      throw new IllegalArgumentException("triggerTokens must be at least 1");
    }
    if (keepRecent < 0) {
      throw new IllegalArgumentException("keepRecent must be at least 0");
    }
  }

  @Override
  public boolean requiresCompaction(SessionState state) {
    return state.lastInputTokens() >= triggerTokens;
  }

  @Override
  public Result compact(SessionState state) {
    List<Message> workingSet = state.messages();
    Context context = Context.of(workingSet);
    int cut = context.pairSafeCut(keepRecent);
    if (cut == 0) {
      // Nothing compactable — a giant tool exchange with no user-text boundary to cut at, for
      // instance. The reducer's non-shrinking-result rule treats this the same as any other
      // skip: proceed uncompacted rather than fail.
      return new Result(workingSet);
    }
    String summary = summarizer.summarize(context.head(cut));
    List<Message> rewritten = new ArrayList<>();
    rewritten.add(Message.user(SUMMARY_PREFIX + summary));
    rewritten.addAll(workingSet.subList(cut, workingSet.size()));
    return new Result(rewritten);
  }
}
