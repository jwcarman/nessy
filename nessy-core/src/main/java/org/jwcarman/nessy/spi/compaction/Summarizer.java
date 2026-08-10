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
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Turns the head of a conversation into prose. The one thing {@code SummarizingCompaction} — the
 * default {@link org.jwcarman.nessy.api.CompactionStrategy} — cannot do itself: call a model.
 */
public interface Summarizer {

  /** Summarizes {@code head} per {@code policy}'s instructions and token budget. */
  Summary summarize(Context head, CompactionPolicy policy);

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
   * {@code config}'s model and system prompt.
   */
  static Summarizer usingProvider(ModelProvider provider, ModelSettings config) {
    return new ProviderSummarizer(provider, config);
  }
}
