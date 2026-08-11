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

/**
 * The floor: remembers everything verbatim, recalls it whole.
 *
 * <p>Safe by construction — legal messages went in, so the returned context cannot be illegal.
 * Idempotency is the consecutive-duplicate rule: a message equal to the last one remembered is the
 * at-least-once re-telling of crash recovery, not new speech, and is dropped.
 */
public final class ListMemory implements Memory {

  private final Map<ConversationId, List<Message>> conversations = new ConcurrentHashMap<>();

  @Override
  public void remember(ConversationId id, Message message) {
    conversations.compute(
        id,
        (key, existing) -> {
          List<Message> messages = existing == null ? new ArrayList<>() : existing;
          if (messages.isEmpty() || !messages.getLast().equals(message)) {
            messages.add(message);
          }
          return messages;
        });
  }

  @Override
  public Context recall(ConversationId id) {
    List<Message> messages = conversations.get(id);
    return Context.of(messages == null ? List.of() : List.copyOf(messages));
  }
}
