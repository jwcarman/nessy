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
package org.jwcarman.nessy.api.conversation;

import java.util.Objects;

/**
 * Tokens spent on one turn, or accumulated across a session.
 *
 * <p>The two cache components are named for the OpenTelemetry GenAI semantic conventions they feed
 * — {@code gen_ai.usage.cache_read.input_tokens} and {@code gen_ai.usage.cache_write.input_tokens}
 * (<a
 * href="https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md">gen-ai-spans.md</a>,
 * both Recommended "When applicable"). Semconv's own note: a cache READ count "SHOULD be included
 * in {@code gen_ai.usage.input_tokens}". A provider that reports neither reports zero for both.
 *
 * @param inputTokens tokens in the prompt
 * @param outputTokens tokens in the completion
 * @param cacheReadInputTokens input tokens served from a provider-managed cache
 * @param cacheWriteInputTokens input tokens written to a provider-managed cache
 */
public record Usage(
    long inputTokens, long outputTokens, long cacheReadInputTokens, long cacheWriteInputTokens) {

  public Usage {
    if (inputTokens < 0
        || outputTokens < 0
        || cacheReadInputTokens < 0
        || cacheWriteInputTokens < 0) {
      throw new IllegalArgumentException("token counts must be non-negative");
    }
  }

  public static Usage zero() {
    return new Usage(0, 0, 0, 0);
  }

  public Usage plus(Usage other) {
    Objects.requireNonNull(other, "other must not be null");
    return new Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheReadInputTokens + other.cacheReadInputTokens,
        cacheWriteInputTokens + other.cacheWriteInputTokens);
  }
}
