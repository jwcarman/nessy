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
package org.jwcarman.nessy.api.model;

/**
 * What one model call cost, in tokens.
 *
 * <p>Reported by the provider rather than estimated — which is why nothing in this API estimates
 * tokens.
 *
 * <p><b>The cache counts are SUBSETS of {@link #inputTokens}, not siblings.</b> That is what the
 * OpenTelemetry GenAI conventions ask for — {@code gen_ai.usage.input_tokens} "SHOULD include all
 * types of input tokens, including cached tokens" — and it is the one rule an adapter has to get
 * right, because each vendor reports it differently: Anthropic's own {@code input_tokens} EXCLUDES
 * both cache counts and must be summed, while Gemini's {@code promptTokenCount} already includes
 * them and must not be. Getting it backwards makes a cache look like a discount on the graph.
 *
 * @param inputTokens tokens read, including whatever was served from or written to cache
 * @param outputTokens tokens generated, including reasoning the provider billed but did not show
 * @param cacheReadInputTokens how many of {@link #inputTokens} came from a cache rather than being
 *     processed afresh — the number that says whether caching is actually paying for itself
 * @param cacheWriteInputTokens how many of {@link #inputTokens} were written INTO a cache, which
 *     some vendors bill at a premium and some do not bill at all
 */
public record Usage(
    long inputTokens, long outputTokens, long cacheReadInputTokens, long cacheWriteInputTokens) {

  public Usage {
    if (inputTokens < 0
        || outputTokens < 0
        || cacheReadInputTokens < 0
        || cacheWriteInputTokens < 0) {
      throw new IllegalArgumentException("token counts must not be negative");
    }
    if (cacheReadInputTokens + cacheWriteInputTokens > inputTokens) {
      // A subset that exceeds its whole means an adapter summed when it should have passed
      // through, and every cache-hit-rate graph built on it would be wrong.
      throw new IllegalArgumentException(
          "cache tokens (%d read + %d written) cannot exceed inputTokens (%d)"
              .formatted(cacheReadInputTokens, cacheWriteInputTokens, inputTokens));
    }
  }

  /** A call with no caching involved, or a provider that does not report it. */
  public Usage(long inputTokens, long outputTokens) {
    this(inputTokens, outputTokens, 0, 0);
  }

  public long totalTokens() {
    return inputTokens + outputTokens;
  }
}
