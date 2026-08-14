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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.internal.Identifiers;

/**
 * One durable piece of mail in a conversation's inbox: a tell that arrived, or a park that
 * resolved. Both carry a time-ordered {@link #id()} minted the same way {@link
 * org.jwcarman.nessy.api.ParkToken#generate()} mints its own — the sanctioned api-to-internal
 * precedent.
 *
 * <p>Arrival-ordered, never prioritized: the inbox has no reordering, no priority queue — items are
 * taken up in the order they were laid down.
 *
 * <p>Sealed-grammar etiquette: core switches over this type are exhaustive with no {@code default}
 * arm.
 */
public sealed interface InboxEntry {

  /** This entry's own time-ordered id. */
  String id();

  /** Words interjected: content the agent was told, not yet folded. */
  record Told(String id, List<ContentBlock> content) implements InboxEntry {
    public Told {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(content, "content must not be null");
      content = List.copyOf(content);
    }
  }

  /**
   * Homework that came back: the call it answers, and what arrived. Re-keyed by call id, not token
   * (design §5) — the loop's resolution routing matches {@link
   * org.jwcarman.nessy.api.conversation.ConversationState#parkedCalls()} by call id, the same
   * pairing the fold has always used.
   */
  record Resolved(String id, String callId, ToolResolution resolution) implements InboxEntry {
    public Resolved {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(callId, "callId must not be null");
      Objects.requireNonNull(resolution, "resolution must not be null");
    }
  }

  static Told told(List<ContentBlock> content) {
    Objects.requireNonNull(content, "content must not be null");
    return new Told(Identifiers.next(), content);
  }

  static Resolved resolved(String callId, ToolResolution resolution) {
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(resolution, "resolution must not be null");
    return new Resolved(Identifiers.next(), callId, resolution);
  }
}
