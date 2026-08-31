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
 * <p><b>Every count is nullable, and null means the provider did not say.</b> That is not the same
 * fact as zero, and the difference is the one a graph turns on: LM Studio reports prompt and
 * completion tokens and has no {@code prompt_tokens_details} at all, so a zero cache count there
 * would be an invention. Averaged into a cache-hit rate, invented zeros read as a cache that is
 * working badly rather than a provider that keeps no such books. A REPORTED zero still means zero
 * and is still worth recording.
 *
 * <p><b>The cache counts are SUBSETS of {@link #inputTokens}, not siblings.</b> That is what the
 * OpenTelemetry GenAI conventions ask for — {@code gen_ai.usage.input_tokens} "SHOULD include all
 * types of input tokens, including cached tokens" — and it is the one rule an adapter has to get
 * right, because each vendor reports it differently: Anthropic's own {@code input_tokens} EXCLUDES
 * both cache counts and must be summed, while Gemini's {@code promptTokenCount} already includes
 * them and must not be. Getting it backwards makes a cache look like a discount on the graph.
 *
 * @param inputTokens tokens read, including whatever was served from or written to cache; null if
 *     the provider did not report it
 * @param outputTokens tokens generated, including reasoning the provider billed but did not show;
 *     null if the provider did not report it
 * @param cacheReadInputTokens how many of {@link #inputTokens} came from a cache rather than being
 *     processed afresh — the number that says whether caching is actually paying for itself. Null
 *     where the provider does not report caching at all, which is a different answer from zero.
 * @param cacheWriteInputTokens how many of {@link #inputTokens} were written INTO a cache, which
 *     some vendors bill at a premium and some do not bill at all; null if not reported
 */
public record Usage(
    Integer inputTokens,
    Integer outputTokens,
    Integer cacheReadInputTokens,
    Integer cacheWriteInputTokens) {

  public Usage {
    requireNotNegative(inputTokens, "inputTokens");
    requireNotNegative(outputTokens, "outputTokens");
    requireNotNegative(cacheReadInputTokens, "cacheReadInputTokens");
    requireNotNegative(cacheWriteInputTokens, "cacheWriteInputTokens");
    // Only checkable when all three numbers are actually present. A partial report is ordinary —
    // a provider that gives an input total and no cache detail is the common case — so absence
    // must not be treated as a violation of a rule it says nothing about.
    if (inputTokens != null
        && cacheReadInputTokens != null
        && cacheWriteInputTokens != null
        && cacheReadInputTokens + cacheWriteInputTokens > inputTokens) {
      // A subset that exceeds its whole means an adapter summed when it should have passed
      // through, and every cache-hit-rate graph built on it would be wrong.
      throw new IllegalArgumentException(
          "cache tokens (%d read + %d written) cannot exceed inputTokens (%d)"
              .formatted(cacheReadInputTokens, cacheWriteInputTokens, inputTokens));
    }
  }

  private static void requireNotNegative(Integer count, String name) {
    if (count != null && count < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  /**
   * A call whose input and output were reported and whose caching was not — the shape of every
   * provider that keeps no cache books, and of one that does but said nothing this time.
   */
  public Usage(int inputTokens, int outputTokens) {
    this(inputTokens, outputTokens, null, null);
  }

  /**
   * The provider closed the stream without saying what the call cost.
   *
   * <p>Distinct from a call that cost nothing: this is the absence of an answer, and anything
   * totalling tokens should skip it rather than add zero.
   */
  public static Usage unreported() {
    return new Usage(null, null, null, null);
  }

  /** Input plus output, or null if either half was never reported. */
  public Integer totalTokens() {
    return inputTokens == null || outputTokens == null ? null : inputTokens + outputTokens;
  }
}
