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
package org.jwcarman.nessy.spi.transcript;

import java.util.List;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.ToolUseBlock;

/**
 * The one open-tail trim every transcript-backed {@link org.jwcarman.nessy.spi.memory.Memory}
 * border needs, shared so {@link org.jwcarman.nessy.spi.memory.TranscriptMemory} and {@link
 * org.jwcarman.nessy.spi.memory.SummarizingMemory} apply exactly the same rule rather than two
 * copies drifting apart. Public because custom {@code ContextHydrator}s must discharge the
 * open-tail border duty from outside this package (spec §2.1).
 */
public final class TranscriptTrim {

  private TranscriptTrim() {}

  /**
   * {@code ConversationLoop} (nessy-core) remembers the model's tool-use message the moment its
   * fold settles, before the loop learns whether the call will park — so a parked conversation's
   * raw telling legitimately ends in an unanswered assistant tool-use message, an illegal trailing
   * shape for {@link org.jwcarman.nessy.api.message.Context}'s wire-safe invariant. {@link
   * org.jwcarman.nessy.spi.memory.Memory#recall} is nonetheless contracted to "return a legal
   * {@code Context}" (see {@code Memory}'s javadoc); dropping that one open tail — the loop's own
   * park-in-progress bookkeeping, not settled dialogue yet — is what keeps a transcript-backed
   * recall honest to that contract for the single-parked-call case, without touching the
   * fold/remember timing itself. Does not cover halt-while-parked — that shape's trailing message
   * is a {@code USER} results message, not an open {@code ASSISTANT} tool-use, so this check never
   * fires for it.
   */
  public static List<Message> withoutOpenTail(List<Message> messages) {
    if (messages.isEmpty()) {
      return messages;
    }
    Message last = messages.getLast();
    boolean openTail =
        last.role() == Role.ASSISTANT
            && last.content().stream().anyMatch(ToolUseBlock.class::isInstance);
    return openTail ? messages.subList(0, messages.size() - 1) : messages;
  }
}
