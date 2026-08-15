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
package org.jwcarman.nessy.tck;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.conversation.Parks.Park;

/**
 * The technology-compatibility kit every {@link Parks} implementation must pass: registration,
 * lookup, the per-conversation read, idempotent re-registration, and survival past resolution —
 * pinned as law rather than left to each implementation's own judgment.
 */
public abstract class ParksContract {

  /** The same registry for the whole of one test, empty at its start. */
  protected abstract Parks parks();

  private static ToolCall toolCall(String id) {
    return new ToolCall(id, "search", JsonNodeFactory.instance.objectNode());
  }

  @Test
  void an_unregistered_token_finds_nothing() {
    assertThat(parks().find(ParkToken.generate())).isEmpty();
  }

  @Test
  void a_registered_park_is_found_by_its_token() {
    ConversationId id = ConversationId.generate();
    ParkToken token = ParkToken.generate();
    Park park = new Park(id, token, toolCall("c1"), "keeper");

    parks().park(park);

    assertThat(parks().find(token)).contains(park);
  }

  @Test
  void re_registering_the_same_token_is_idempotent() {
    ConversationId id = ConversationId.generate();
    ParkToken token = ParkToken.generate();
    Park park = new Park(id, token, toolCall("c1"), "keeper");

    parks().park(park);
    parks().park(park);

    assertThat(parks().forConversation(id)).containsExactly(park);
  }

  @Test
  void for_conversation_returns_every_wait_ever_registered_for_that_id() {
    ConversationId id = ConversationId.generate();
    Park first = new Park(id, ParkToken.generate(), toolCall("c1"), "keeper");
    Park second = new Park(id, ParkToken.generate(), toolCall("c2"), "keeper");

    parks().park(first);
    parks().park(second);

    assertThat(parks().forConversation(id)).containsExactlyInAnyOrder(first, second);
  }

  @Test
  void for_conversation_never_returns_another_conversations_waits() {
    ConversationId mine = ConversationId.generate();
    ConversationId theirs = ConversationId.generate();
    Park park = new Park(mine, ParkToken.generate(), toolCall("c1"), "keeper");

    parks().park(park);
    parks().park(new Park(theirs, ParkToken.generate(), toolCall("c2"), "keeper"));

    assertThat(parks().forConversation(mine)).containsExactly(park);
  }

  @Test
  void an_unknown_conversation_has_no_registered_waits() {
    assertThat(parks().forConversation(ConversationId.generate())).isEmpty();
  }

  @Test
  void a_find_does_not_consume_the_entry() {
    ConversationId id = ConversationId.generate();
    ParkToken token = ParkToken.generate();
    Park park = new Park(id, token, toolCall("c1"), "keeper");
    parks().park(park);

    // A resolution is answered by the conversation's own inbox and fold, not this registry —
    // resolving the wait leaves nothing here for the registry to react to. The entry stays put,
    // the same keep-forever posture the retired single-use token table already had: reading it
    // once must not remove it, so a second read still finds it.
    assertThat(parks().find(token)).contains(park);
    assertThat(parks().find(token)).contains(park);
  }
}
