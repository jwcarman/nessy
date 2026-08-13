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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.ToolUseBlock;

/**
 * The floor: remembers everything verbatim, recalls it whole.
 *
 * <p>Safe by construction — legal messages went in, so the returned context cannot be illegal —
 * once {@link #recall} trims the one legitimate exception: the loop remembers the model's tool-use
 * message the moment its fold settles, before it learns whether the call will park, so a parked
 * conversation's raw telling can legitimately end in an unanswered tool-use message. That open tail
 * — the loop's own park-in-progress bookkeeping, not settled dialogue yet — is dropped before the
 * trimmed list reaches {@link Context#of}, the same narrow trim {@code JdbcMemory} applies at
 * recall time (see its {@code withoutOpenTail}). Idempotency is the consecutive-duplicate rule: a
 * message equal to the last one remembered is the at-least-once re-telling of crash recovery, not
 * new speech, and is dropped.
 *
 * <p>Every value ever stored under a key is an immutable snapshot: {@link #remember} always builds
 * and stores a fresh {@link List#copyOf}, never mutates a list already published to the map. {@link
 * #recall}'s unsynchronized {@link Map#get} is therefore safe by construction — {@link
 * ConcurrentHashMap}'s per-key happens-before on the reference swap is all the safety a read of an
 * immutable value ever needs, with no risk of observing a torn or concurrently-modified list.
 *
 * <p>Every conversation it has ever been told about grows without eviction for the life of the
 * process — there is no forgetting, no cap, no compaction. That suits a process that owns its
 * sessions, not a long-lived multi-tenant server.
 */
public final class ListMemory implements Memory {

  private final Map<ConversationId, List<Message>> conversations = new ConcurrentHashMap<>();

  @Override
  public void remember(ConversationId id, Message message) {
    conversations.compute(
        id,
        (key, existing) -> {
          if (existing != null && !existing.isEmpty() && existing.getLast().equals(message)) {
            return existing;
          }
          List<Message> appended = new ArrayList<>(existing == null ? List.of() : existing);
          appended.add(message);
          return List.copyOf(appended);
        });
  }

  @Override
  public Context recall(ConversationId id) {
    List<Message> messages = conversations.get(id);
    return Context.of(withoutOpenTail(messages == null ? List.of() : messages));
  }

  /**
   * {@code ConversationLoop} (nessy-core) remembers the model's tool-use message the moment its
   * fold settles, before the loop learns whether the call will park — so a parked conversation's
   * raw telling legitimately ends in an unanswered assistant tool-use message, an illegal trailing
   * shape for {@link Context}'s wire-safe invariant. {@link Memory#recall} is nonetheless
   * contracted to "return a legal {@code Context}" (see {@code Memory}'s javadoc); dropping that
   * one open tail — the loop's own park-in-progress bookkeeping, not settled dialogue yet — is what
   * keeps this implementation honest to that contract without touching the fold/remember timing
   * itself. Mirrors {@code JdbcMemory}'s own {@code withoutOpenTail}.
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
