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
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * The stale save's report: it names the conversation and both versions, so the loser of a fenced
 * race can reload from the right base instead of guessing.
 */
class StaleStateExceptionTest {

  @Test
  void names_the_conversation_and_both_versions() {
    ConversationId id = ConversationId.generate();

    StaleStateException stale = new StaleStateException(id, 3L, 5L);

    assertThat(stale.id()).isEqualTo(id);
    assertThat(stale.expected()).isEqualTo(3L);
    assertThat(stale.found()).isEqualTo(5L);
    assertThat(stale)
        .hasMessageContaining(id.value())
        .hasMessageContaining("3")
        .hasMessageContaining("5");
  }
}
