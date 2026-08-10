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
package org.jwcarman.nessy.spi.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;

class InMemoryConversationStoreTest {

  private final ConversationStore store = new InMemoryConversationStore();
  private final ConversationId id = new ConversationId("s1");

  @Test
  void loading_an_unknown_session_is_empty() {
    assertThat(store.load(id)).isEmpty();
  }

  @Test
  void saved_state_comes_back() {
    ConversationState state =
        ConversationState.newConversation(id).with(ConversationStatus.COMPLETE);

    store.save(state);

    assertThat(store.load(id)).contains(state);
  }

  @Test
  void saving_again_replaces() {
    store.save(ConversationState.newConversation(id).with(ConversationStatus.AWAITING_MODEL));
    store.save(ConversationState.newConversation(id).with(ConversationStatus.COMPLETE));

    assertThat(store.load(id).orElseThrow().status()).isEqualTo(ConversationStatus.COMPLETE);
  }

  @Test
  void a_token_can_be_consumed_exactly_once() {
    ParkToken token = ParkToken.generate();

    assertThat(store.consumeToken(token)).isTrue();
    assertThat(store.consumeToken(token)).isFalse();
  }

  @Test
  void in_memory_factory_returns_a_working_store() {
    ConversationStore store = ConversationStore.inMemory();
    store.save(ConversationState.newConversation(id));

    assertThat(store.load(id)).isPresent();
  }
}
