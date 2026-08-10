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
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.CompactionStrategy;
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.Usage;

/**
 * The default {@link CompactionStrategy}: cuts the working set at the last pair-safe boundary that
 * still leaves {@code policy.keepRecentMessages()} messages verbatim, hands the head to a {@link
 * Summarizer}, and splices the summary back in front of the untouched tail.
 *
 * <p>Package-private: reached only through {@link CompactionStrategies#summarizing}, never
 * constructed directly.
 */
record SummarizingCompaction(CompactionPolicy policy, Summarizer summarizer)
    implements CompactionStrategy {

  /**
   * Moved here from {@code Reducer}: summary formatting is this strategy's business, not the
   * reducer's. Kept as the exact string the reducer used to own.
   */
  static final String SUMMARY_PREFIX = "[Conversation summary — earlier turns compacted]\n";

  SummarizingCompaction {
    Objects.requireNonNull(policy, "policy must not be null");
    Objects.requireNonNull(summarizer, "summarizer must not be null");
  }

  @Override
  public boolean requiresCompaction(SessionState state) {
    return policy.trigger().shouldCompact(state);
  }

  @Override
  public Result compact(List<Message> workingSet) {
    Context context = Context.of(workingSet);
    int cut = context.pairSafeCut(policy.keepRecentMessages());
    if (cut == 0) {
      // Nothing compactable — a giant tool exchange with no user-text boundary to cut at, for
      // instance. The reducer's non-shrinking-result rule treats this the same as any other
      // skip: proceed uncompacted rather than fail.
      return new Result(workingSet, Usage.zero());
    }
    Summarizer.Summary summary = summarizer.summarize(context.head(cut), policy);
    List<Message> rewritten = new ArrayList<>();
    rewritten.add(Message.user(SUMMARY_PREFIX + summary.text()));
    rewritten.addAll(workingSet.subList(cut, workingSet.size()));
    return new Result(rewritten, summary.usage());
  }
}
