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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * The border law, observed at the seam (spec §4): retention stays whole, recall is trimmed to the
 * window, and the trim is pair-safe — a Context self-validates on construction, so recall returning
 * at all proves wire-legality; these tests pin the bound and the pairing behavior.
 */
class WindowedMemoryTest {

  @Test
  void recall_is_bounded_by_the_window() {
    WindowedMemory memory = new WindowedMemory(4);
    ConversationId id = ConversationId.generate();
    for (int i = 0; i < 10; i++) {
      memory.remember(id, Message.user("round " + i));
      memory.remember(id, Message.assistant(List.of()));
    }

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).hasSize(4);
    assertThat(recalled.messages().getFirst().content()).isNotEmpty();
  }

  @Test
  void a_tool_exchange_survives_the_cut_whole_or_not_at_all() {
    WindowedMemory memory = new WindowedMemory(3);
    ConversationId id = ConversationId.generate();
    ToolCall call = new ToolCall("c1", "check_vitals", JsonNodeFactory.instance.objectNode());
    memory.remember(id, Message.user("round 1"));
    memory.remember(id, Message.assistant(List.of(new ToolUseBlock(call))));
    memory.remember(id, Message.toolResults(List.of(new ToolResultBlock("c1", "vitals", false))));
    memory.remember(id, Message.user("round 2"));
    memory.remember(id, Message.assistant(List.of()));

    Context recalled = memory.recall(id);

    // keepRecent cuts only at a genuine user turn, so the exchange either survives with its
    // results or is dropped entirely; Context's own constructor makes a split unconstructible.
    boolean hasToolUse =
        recalled.messages().stream()
            .flatMap(message -> message.content().stream())
            .anyMatch(ToolUseBlock.class::isInstance);
    boolean hasToolResult =
        recalled.messages().stream()
            .flatMap(message -> message.content().stream())
            .anyMatch(ToolResultBlock.class::isInstance);
    assertThat(hasToolUse).isEqualTo(hasToolResult);
    assertThat(recalled.messages()).isNotEmpty();
    assertThat(recalled.messages().getLast().content()).isEmpty();
  }

  @Test
  void a_window_below_one_is_rejected() {
    assertThatThrownBy(() -> new WindowedMemory(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
