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
package org.jwcarman.nessy.inference;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What one call cost, and on which model.
 *
 * <p><b>The model is here rather than beside it, because tokens without a model cannot be
 * priced.</b> Keeping them in one value means nobody can hold a count they have no rate for --
 * which is an invariant and not just a convention: a {@code Usage} that reports any count must name
 * the model it was counted on. It is the model this call was <em>billed as</em>: an adapter reports
 * what the vendor said answered when the vendor says so -- which may be a dated build of the alias
 * that was asked for -- and what it asked for when the vendor does not.
 *
 * <p>The model may be absent only when nothing at all was counted, which is what {@link
 * #unreported()} is: a scripted provider in a test, or an effect that never reached a vendor, has
 * nothing to price and nothing to price it on.
 *
 * <p><b>Every count is nullable, and null is not zero.</b> Null means nobody counted it; zero means
 * it was counted and was zero. A reply that cost nothing and a reply nobody measured are different
 * facts, and a graph cannot tell a missing series from a genuine zero. Absence is per field rather
 * than all-or-nothing because that is what the vendors actually do: Gemini makes even the input
 * count optional, and an OpenAI-compatible server like LM Studio reports input and output with no
 * cache detail of any kind.
 *
 * <h2>What the counts mean, which is not what every vendor's wire says</h2>
 *
 * <p><b>{@code inputTokens} is ALL input processed, cached tokens included.</b> The vendors
 * disagree here: OpenAI's {@code prompt_tokens} already includes what was read from cache, while
 * Anthropic's {@code input_tokens} excludes it and reports the cache counts separately. Storing
 * each vendor's own spelling would mean every reader had to know which vendor produced a number
 * before it could add two of them together -- so adapters normalise to this definition instead, and
 * {@code inputTokens + outputTokens} is the total tokens processed whoever answered.
 *
 * <p><b>{@code cacheReadTokens} and {@code cacheWriteTokens} are a breakdown of the input, not an
 * addition to it.</b> They exist because the three prices differ by an order of magnitude -- a
 * cache read is around a tenth of the ordinary input rate and a write rather more than it -- so a
 * single input count cannot be turned into money. Anthropic splits a write again by how long it is
 * kept; that is summed here, because one vendor's pricing tiers do not generalise and its own
 * invoice remains the authority on them.
 *
 * <p><b>{@code reasoningTokens} is a breakdown of the output</b>, for the same reason and not for
 * billing: thinking is charged at the ordinary output rate, so it changes no arithmetic. It answers
 * a different question -- whether an expensive turn was expensive because it produced a lot or
 * because it thought a lot -- which is otherwise unanswerable from the outside.
 *
 * <h2>Where each number comes from, and which are derived</h2>
 *
 * <p>Measured against the SDKs this ships with. Everything below is the vendor's own figure except
 * where it says otherwise.
 *
 * <table>
 *   <caption>Per-vendor mapping</caption>
 *   <tr><th>       <th>input                           <th>cache read <th>cache write <th>reasoning
 *   <tr><td>Anthropic <td><b>derived</b>: {@code input_tokens + cache_read + cache_creation}
 *                     <td>{@code cache_read_input_tokens} <td>{@code cache_creation_input_tokens}
 *                     <td>{@code output_tokens_details}
 *   <tr><td>OpenAI    <td>{@code prompt_tokens}, which already includes cache
 *                     <td>{@code prompt_tokens_details.cached_tokens}
 *                     <td>{@code prompt_tokens_details.cache_write_tokens}
 *                     <td>{@code completion_tokens_details.reasoning_tokens}
 *   <tr><td>Gemini    <td>{@code promptTokenCount}, which already includes cache
 *                     <td>{@code cachedContentTokenCount} <td>unreported, always
 *                     <td>{@code thoughtsTokenCount}
 *   <tr><td>Bedrock   <td><b>derived</b>: {@code inputTokens + cacheRead + cacheWrite}
 *                     <td>{@code cacheReadInputTokens}    <td>{@code cacheWriteInputTokens}
 *                     <td>unreported, always
 * </table>
 *
 * <p><b>Why deriving is safe in the two places it happens, and why it is not done in the
 * others.</b> Anthropic and Bedrock support caching and report it, so a count they leave out means
 * nothing was cached -- zero, and the sum is exact. OpenAI and Gemini already include cache in
 * their input figure, so nothing needs adding; going the other way and subtracting to find the
 * uncached part would be unsafe, because a compatible server such as LM Studio omits cache detail
 * entirely and the subtraction would discard an input count that was actually reported.
 *
 * <p><b>What is deliberately not modelled.</b> Anthropic splits a cache write by how long it is
 * kept -- five minutes or an hour, priced differently -- and that is summed here; its invoice
 * remains the authority on the split. The vendors' own totals are not kept, because {@link
 * #totalTokens()} computes the same thing from parts that are individually useful. Audio,
 * prediction and per-modality details are not kept because nothing here sends or receives them.
 */
public record Usage(
    @Nullable String model,
    @Nullable Integer inputTokens,
    @Nullable Integer outputTokens,
    @Nullable Integer cacheReadTokens,
    @Nullable Integer cacheWriteTokens,
    @Nullable Integer reasoningTokens) {

  public Usage {
    if (model != null && model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    requireCounted("inputTokens", inputTokens);
    requireCounted("outputTokens", outputTokens);
    requireCounted("cacheReadTokens", cacheReadTokens);
    requireCounted("cacheWriteTokens", cacheWriteTokens);
    requireCounted("reasoningTokens", reasoningTokens);
    // The whole point of holding the model here is that a count is never unpriceable.
    if (model == null
        && (inputTokens != null
            || outputTokens != null
            || cacheReadTokens != null
            || cacheWriteTokens != null
            || reasoningTokens != null)) {
      throw new IllegalArgumentException("a counted usage must name the model it was counted on");
    }
  }

  private static void requireCounted(String name, @Nullable Integer count) {
    if (count != null && count < 0) {
      throw new IllegalArgumentException(name + " must not be negative: " + count);
    }
  }

  /** The ordinary case: a vendor that counted what went in and what came out, and nothing else. */
  public static Usage of(
      String model, @Nullable Integer inputTokens, @Nullable Integer outputTokens) {
    return new Usage(model, inputTokens, outputTokens, null, null, null);
  }

  /**
   * Nobody counted anything, and nothing here says what would have answered.
   *
   * <p>A scripted provider in a test, an effect that never reached a vendor. Distinct from every
   * count being zero, which would claim the call was free.
   */
  public static Usage unreported() {
    return new Usage(null, null, null, null, null, null);
  }

  /**
   * Nobody counted, but we know what answered.
   *
   * <p>A stream cut short, or a compatible server that omits usage entirely: the call was really
   * made, on a model we can name, and no count came back from it.
   */
  public static Usage unreported(String model) {
    return new Usage(
        Objects.requireNonNull(model, "model must not be null"), null, null, null, null, null);
  }

  /**
   * The same counts, with what was read from cache -- part of {@link #inputTokens()}, not extra.
   */
  public Usage withCacheRead(@Nullable Integer tokens) {
    return new Usage(model, inputTokens, outputTokens, tokens, cacheWriteTokens, reasoningTokens);
  }

  /** The same counts, with what was written to cache -- part of {@link #inputTokens()}. */
  public Usage withCacheWrite(@Nullable Integer tokens) {
    return new Usage(model, inputTokens, outputTokens, cacheReadTokens, tokens, reasoningTokens);
  }

  /** The same counts, with what was spent thinking -- part of {@link #outputTokens()}. */
  public Usage withReasoning(@Nullable Integer tokens) {
    return new Usage(model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, tokens);
  }

  /** Whether any count at all was reported. */
  public boolean counted() {
    return inputTokens != null
        || outputTokens != null
        || cacheReadTokens != null
        || cacheWriteTokens != null
        || reasoningTokens != null;
  }

  /**
   * Everything processed, in and out, or null if neither was counted.
   *
   * <p>Cache and reasoning are deliberately absent from this sum: both are already inside one of
   * the two numbers being added, so including them would double-count. One side counted and the
   * other not sums to the side that was, which is the most that can honestly be said.
   */
  public @Nullable Integer totalTokens() {
    if (inputTokens == null && outputTokens == null) {
      return null;
    }
    return (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
  }
}
