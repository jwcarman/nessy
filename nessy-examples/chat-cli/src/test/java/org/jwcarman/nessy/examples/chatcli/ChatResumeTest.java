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
package org.jwcarman.nessy.examples.chatcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;

@DisplayName("Resuming a conversation with nessy.console.agent")
class ChatResumeTest {

  @Nested
  class When_the_property_names_a_conversation {

    @Test
    void that_conversation_is_the_one_resumed() {
      UUID id = UUID.randomUUID();

      assertThat(Chat.resumed(id.toString())).contains(new AgentId(id));
    }

    @Test
    void surrounding_whitespace_is_ignored() {
      UUID id = UUID.randomUUID();

      assertThat(Chat.resumed("  " + id + " ")).contains(new AgentId(id));
    }

    @Test
    void something_that_is_not_an_id_is_refused() {
      assertThatThrownBy(() -> Chat.resumed("not-an-id"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class When_the_property_is_absent {

    @Test
    void nothing_is_resumed_so_the_console_starts_a_new_conversation() {
      assertThat(Chat.resumed(null)).isEmpty();
    }

    @Test
    void a_blank_value_is_the_same_as_absent() {
      assertThat(Chat.resumed("  ")).isEmpty();
    }
  }
}
