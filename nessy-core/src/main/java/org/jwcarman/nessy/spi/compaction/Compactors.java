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

/**
 * Home for {@link Compactor} factories. {@link Compactor#disabled()} needs nothing beyond {@code
 * spi.compaction} itself and stays on the interface; the summarizing default needs a {@link
 * Summarizer} to construct, so it lives here instead, behind a builder that gives each knob to
 * whichever piece of the default actually owns it.
 */
public final class Compactors {

  private Compactors() {}

  /**
   * The default compactor: triggers once measured input tokens cross {@code triggerTokens}, and
   * summarizes the pair-safe head of the working set through {@code summarizer}, keeping the last
   * {@code keepRecent} messages verbatim. See {@link SummarizingCompaction} for the exact splicing
   * rule.
   */
  public static SummarizingBuilder summarizing(Summarizer summarizer) {
    return new SummarizingBuilder(summarizer);
  }

  /** Builds the summarizing default, one knob per owner. */
  public static final class SummarizingBuilder {

    private final Summarizer summarizer;
    private long triggerTokens = 100_000;
    private int keepRecent = 10;

    private SummarizingBuilder(Summarizer summarizer) {
      this.summarizer = Objects.requireNonNull(summarizer, "summarizer must not be null");
    }

    /**
     * Fires once {@code ConversationState.lastInputTokens()} reaches {@code triggerTokens}. Shares
     * one underlying value with {@link #window}; whichever of the two is called last wins.
     */
    public SummarizingBuilder triggerTokens(long triggerTokens) {
      if (triggerTokens < 1) {
        throw new IllegalArgumentException("triggerTokens must be at least 1");
      }
      this.triggerTokens = triggerTokens;
      return this;
    }

    /**
     * Derives {@link #triggerTokens} from a declared context window: fires at roughly 80% of the
     * room left over after reserving {@code maxTokens} for the model's reply, so the summarization
     * call itself still fits. Shares one underlying value with {@link #triggerTokens}; whichever of
     * the two is called last wins.
     */
    public SummarizingBuilder window(long window, long maxTokens) {
      if (window <= maxTokens) {
        throw new IllegalArgumentException("window must be greater than maxTokens");
      }
      this.triggerTokens = Math.max(1, (long) (0.8 * (window - maxTokens)));
      return this;
    }

    /** How many of the most recent messages survive compaction verbatim. Default 10. */
    public SummarizingBuilder keepRecent(int keepRecent) {
      if (keepRecent < 0) {
        throw new IllegalArgumentException("keepRecent must be at least 0");
      }
      this.keepRecent = keepRecent;
      return this;
    }

    public Compactor build() {
      return new SummarizingCompaction(summarizer, triggerTokens, keepRecent);
    }
  }
}
