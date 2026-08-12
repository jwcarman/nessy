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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;

class InMemoryConversationStoreTest extends ConversationStoreContract {

  @Override
  protected ConversationStore newStore() {
    return ConversationStore.inMemory();
  }

  @Test
  void in_memory_factory_returns_a_working_store() {
    ConversationId id = ConversationId.generate();
    ConversationStore inMemoryStore = ConversationStore.inMemory();

    inMemoryStore.save(ConversationState.newConversation(id), List.of());

    assertThat(inMemoryStore.load(id)).isPresent();
  }
}
