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
package org.jwcarman.nessy.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.ToolResult;

class MessageTest {

  @Test
  void user_message_wraps_text_in_a_block() {
    Message message = Message.user("hello");

    assertThat(message.role()).isEqualTo(Role.USER);
    assertThat(message.content()).containsExactly(new TextBlock("hello"));
  }

  @Test
  void tool_results_are_carried_on_a_user_message() {
    ToolResultBlock block = new ToolResultBlock("call_1", "contents", false);

    Message message = Message.toolResults(List.of(block));

    assertThat(message.role()).isEqualTo(Role.USER);
    assertThat(message.content()).containsExactly(block);
  }

  @Test
  void content_is_defensively_copied() {
    List<ContentBlock> mutable = new ArrayList<>();
    mutable.add(new TextBlock("first"));

    Message message = new Message(Role.ASSISTANT, mutable);
    mutable.add(new TextBlock("sneaked in"));

    assertThat(message.content()).hasSize(1);
  }

  @Test
  void content_is_unmodifiable() {
    Message message = Message.user("hello");
    List<ContentBlock> content = message.content();
    TextBlock block = new TextBlock("nope");

    assertThatThrownBy(() -> content.add(block)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void tool_result_factories_set_the_error_flag() {
    assertThat(ToolResult.ok("fine").isError()).isFalse();
    assertThat(ToolResult.error("boom").isError()).isTrue();
    assertThat(ToolResult.error("boom").content()).isEqualTo("boom");
  }

  @Test
  void random_session_ids_are_distinct() {
    assertThat(ConversationId.generate()).isNotEqualTo(ConversationId.generate());
  }

  @Test
  void generated_session_ids_are_time_ordered_uuidv7() {
    assertThat(UUID.fromString(ConversationId.generate().value()).version()).isEqualTo(7);
  }
}
