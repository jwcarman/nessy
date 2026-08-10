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

import java.util.Objects;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Turns the head of a conversation into prose. The one thing {@code SummarizingCompaction} — the
 * default {@link Compactor} — cannot do itself: call a model.
 *
 * <p>Configuration bakes at construction rather than arriving per call: what to ask for and how
 * much room the reply gets are facts about the summarizer, not about any one summarization.
 */
public interface Summarizer {

  /**
   * The default instructions handed to the summarizer by {@link #usingProvider(ModelProvider,
   * ModelSettings)}.
   */
  String DEFAULT_INSTRUCTIONS =
      "Summarize the conversation so far for your own future reference: goals, decisions, facts"
          + " established, tool results that matter, and open questions. Be dense and factual;"
          + " omit pleasantries.";

  /** Summarizes {@code head} per this summarizer's baked-in instructions and token budget. */
  Summary summarize(Context head);

  /**
   * @param text the summary prose. Blank text is accepted by this record — a producer decides for
   *     itself whether that is a failure; {@link ProviderSummarizer}, for one, treats it as one.
   * @param usage what the summarization call cost
   */
  record Summary(String text, Usage usage) {

    public Summary {
      Objects.requireNonNull(text, "text must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /**
   * The production summarizer: an ordinary, tool-free model call over {@code provider}, using
   * {@code config}'s model and system prompt, asking for {@code instructions} and capping the reply
   * at {@code summaryMaxTokens}.
   */
  static Summarizer usingProvider(
      ModelProvider provider, ModelSettings config, int summaryMaxTokens, String instructions) {
    return new ProviderSummarizer(provider, config, summaryMaxTokens, instructions);
  }

  /**
   * {@link #usingProvider(ModelProvider, ModelSettings, int, String)} with this codebase's
   * defaults: a 2,048-token summary ceiling and {@link #DEFAULT_INSTRUCTIONS}.
   */
  static Summarizer usingProvider(ModelProvider provider, ModelSettings config) {
    return usingProvider(provider, config, 2_048, DEFAULT_INSTRUCTIONS);
  }
}
