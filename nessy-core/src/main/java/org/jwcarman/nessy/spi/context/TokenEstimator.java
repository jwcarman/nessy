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
package org.jwcarman.nessy.spi.context;

import org.jwcarman.nessy.api.ContentBlock;
import org.jwcarman.nessy.api.ImageBlock;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.RedactedThinkingBlock;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.ToolUseBlock;

/**
 * Manufactures the message-level token figure that models never report.
 *
 * <p>Providers report usage per call, not per message. This seam recomputes an honest estimate on
 * demand, on the read path only: budget-aware projections, sizing a summarizer's head, offline
 * analysis over journal content. It complements the measured trigger and never replaces it —
 * compaction keeps triggering on the provider's own exact count.
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
              case TextBlock text -> text.text().length();
              case ToolResultBlock toolResult -> toolResult.content().length();
              case ImageBlock ignored -> 0;
              case ThinkingBlock ignored -> 0;
              case RedactedThinkingBlock ignored -> 0;
              case ToolUseBlock ignored -> 0;
            };
      }
      return Math.max(1, characters / 4);
    };
  }
}
