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

import org.jwcarman.nessy.api.compaction.CompactionPolicy;
import org.jwcarman.nessy.api.compaction.CompactionStrategy;

/**
 * Home for {@link CompactionStrategy} factories that need an {@code spi} type and therefore cannot
 * live on the {@code api} interface itself (see {@link CompactionStrategy}'s javadoc for why).
 * {@link CompactionStrategy#disabled()} needs nothing from {@code spi} and stays where it is; this
 * is the only other factory today.
 */
public final class CompactionStrategies {

  private CompactionStrategies() {}

  /**
   * The default strategy: {@code policy.trigger()} decides when to compact, and {@code summarizer}
   * turns the pair-safe head of the working set into one summary message, leaving the tail
   * untouched. See {@link SummarizingCompaction} for the exact splicing rule.
   */
  public static CompactionStrategy summarizing(CompactionPolicy policy, Summarizer summarizer) {
    return new SummarizingCompaction(policy, summarizer);
  }
}
