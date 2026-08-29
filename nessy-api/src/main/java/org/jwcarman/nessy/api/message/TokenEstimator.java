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
package org.jwcarman.nessy.api.message;

/**
 * Manufactures the message-level token figure that models never report.
 *
 * <p>Providers report usage per call, not per message. This seam recomputes an honest estimate on
 * demand, on the read path only: budget-aware projections, sizing a summarizer's head, offline
 * analysis over stored content. It complements the provider's own measured usage and never replaces
 * it — anything that must trigger on an exact cost still reads the provider's own reported count.
 *
 * <p>Lives beside {@link Context} because {@link Context#tokens(TokenEstimator)} and {@link
 * Context#limitTokens(long, TokenEstimator)} — two verbs of the edit algebra (§10.8) — take it
 * directly: {@code api} may not depend on {@code spi} (see {@code ZoneBoundariesTest}), so a type
 * in {@code Context}'s own public signature has to live in {@code api} too.
 */
public interface TokenEstimator {

  /** An honest estimate for one message; models never report this figure. */
  long estimate(Message message);

  /**
   * Total characters of textual content ({@link TextBlock} text plus {@link ToolResultBlock}
   * content) divided by four, floored at one token per message.
   */
  static TokenEstimator heuristic() {
    return message -> {
      long characters = 0;
      for (ContentBlock block : message.content()) {
        characters +=
            switch (block) {
              case TextBlock(String text) -> text.length();
              case ToolResultBlock result -> result.text().length();
              case ImageBlock _, ThinkingBlock _, RedactedThinkingBlock _, ToolUseBlock _ -> 0;
            };
      }
      return Math.max(1, characters / 4);
    };
  }
}
