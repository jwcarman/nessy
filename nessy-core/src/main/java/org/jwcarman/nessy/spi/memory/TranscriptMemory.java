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
package org.jwcarman.nessy.spi.memory;

import java.util.List;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.ToolUseBlock;

/**
 * The floor: remembers everything verbatim through a transcript, recalls it whole.
 *
 * <p>Legal messages went in, so {@link #recall} trims the one legitimate exception before the
 * returned context can be illegal: the loop remembers the model's tool-use message the moment its
 * fold settles, before it learns whether the call will park, so a parked conversation's raw telling
 * can legitimately end in an unanswered tool-use message. That open tail — the loop's own
 * park-in-progress bookkeeping, not settled dialogue yet — is dropped before the trimmed list
 * reaches {@link Context#of}. This does not make {@link #recall} total: a halt while a sibling call
 * is still parked answers {@link ConversationState#pendingCalls()} but not {@link
 * ConversationState#parkedCalls()} (a recorded follow-up), so the flushed results message can
 * answer only some of a prior tool-use message's ids — a shape this trim does not target and {@link
 * Context#of} still rejects. Idempotency is the transcript's own no-stutter rule, not reimplemented
 * here.
 *
 * <p>Two {@code TranscriptMemory} instances built over the same {@link Transcript} are two windows
 * on one log, not two logs: the seam is the storage, the memory is the policy.
 */
public final class TranscriptMemory implements Memory {

  private final Transcript transcript;

  public TranscriptMemory(Transcript transcript) {
    this.transcript = transcript;
  }

  @Override
  public void remember(ConversationId id, Message message) {
    transcript.append(id, message); // idempotency is the transcript's no-stutter rule
  }

  @Override
  public Context recall(ConversationId id) {
    List<Message> messages = transcript.all(id).stream().map(Transcript.Entry::message).toList();
    return Context.of(withoutOpenTail(messages));
  }

  /**
   * {@code ConversationLoop} (nessy-core) remembers the model's tool-use message the moment its
   * fold settles, before the loop learns whether the call will park — so a parked conversation's
   * raw telling legitimately ends in an unanswered assistant tool-use message, an illegal trailing
   * shape for {@link Context}'s wire-safe invariant. {@link Memory#recall} is nonetheless
   * contracted to "return a legal {@code Context}" (see {@code Memory}'s javadoc); dropping that
   * one open tail — the loop's own park-in-progress bookkeeping, not settled dialogue yet — is what
   * keeps this implementation honest to that contract for the single-parked-call case, without
   * touching the fold/remember timing itself. Does not cover halt-while-parked (see the class
   * javadoc) — that shape's trailing message is a {@code USER} results message, not an open {@code
   * ASSISTANT} tool-use, so this check never fires for it.
   */
  private static List<Message> withoutOpenTail(List<Message> messages) {
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
