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
package org.jwcarman.nessy.api;

import java.util.Objects;

/**
 * Configures stateful compaction: when to trigger it, how much of the transcript to summarize away
 * versus keep verbatim, and what to ask the summarizer for.
 *
 * @param trigger decides when the settled conversation should be compacted
 * @param keepRecentMessages how many of the most recent messages survive compaction verbatim
 * @param summaryMaxTokens the ceiling on the summarizer's own reply
 * @param instructions what to ask the summarizer for
 */
public record CompactionPolicy(
    CompactionTrigger trigger, int keepRecentMessages, int summaryMaxTokens, String instructions) {

  /** The default instructions handed to the summarizer by {@link #defaults()}. */
  public static final String DEFAULT_INSTRUCTIONS =
      "Summarize the conversation so far for your own future reference: goals, decisions, facts"
          + " established, tool results that matter, and open questions. Be dense and factual;"
          + " omit pleasantries.";

  public CompactionPolicy {
    Objects.requireNonNull(trigger, "trigger must not be null");
    if (keepRecentMessages < 0) {
      throw new IllegalArgumentException("keepRecentMessages must be at least 0");
    }
    if (summaryMaxTokens < 1) {
      throw new IllegalArgumentException("summaryMaxTokens must be at least 1");
    }
    Objects.requireNonNull(instructions, "instructions must not be null");
  }

  /** Compaction enabled, triggering at 100k measured input tokens. */
  public static CompactionPolicy defaults() {
    return new CompactionPolicy(
        CompactionTrigger.atTokens(100_000), 10, 2_048, DEFAULT_INSTRUCTIONS);
  }

  /**
   * Compaction effectively off: the trigger never fires, so the reducer never emits {@code
   * Effect.Compact}.
   */
  public static CompactionPolicy disabled() {
    return new CompactionPolicy(CompactionTrigger.never(), 10, 2_048, DEFAULT_INSTRUCTIONS);
  }
}
