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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ContextTest {

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  private static Message assistantText(String text) {
    return Message.assistant(List.of(new TextBlock(text)));
  }

  @Nested
  class Validity {

    @Test
    void a_plain_conversation_is_valid() {
      Context context = Context.of(List.of(Message.user("hi"), assistantText("hello")));

      assertThat(context.messages()).hasSize(2);
    }

    @Test
    void a_completed_tool_exchange_is_valid() {
      Message assistant =
          Message.assistant(List.of(new ToolUseBlock(call("c1")), new ToolUseBlock(call("c2"))));
      Message results =
          Message.toolResults(
              List.of(
                  new ToolResultBlock("c1", "ok", false), new ToolResultBlock("c2", "ok", false)));

      Context context = Context.of(List.of(assistant, results));

      assertThat(context.messages()).containsExactly(assistant, results);
    }

    @Test
    void an_unanswered_tool_use_is_rejected() {
      Message assistant = Message.assistant(List.of(new ToolUseBlock(call("c1"))));

      assertThatThrownBy(() -> Context.of(List.of(assistant)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c1");
    }

    @Test
    void a_partial_results_message_is_rejected() {
      Message assistant =
          Message.assistant(List.of(new ToolUseBlock(call("c1")), new ToolUseBlock(call("c2"))));
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));

      assertThatThrownBy(() -> Context.of(List.of(assistant, results)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c2");
    }

    @Test
    void a_result_for_an_unknown_id_is_rejected() {
      Message assistant = Message.assistant(List.of(new ToolUseBlock(call("c1"))));
      Message results =
          Message.toolResults(
              List.of(
                  new ToolResultBlock("c1", "ok", false),
                  new ToolResultBlock("unknown", "ok", false)));

      assertThatThrownBy(() -> Context.of(List.of(assistant, results)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown");
    }

    @Test
    void a_result_outside_an_answering_message_is_rejected() {
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));

      assertThatThrownBy(() -> Context.of(List.of(Message.user("hi"), results)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c1");
    }

    @Test
    void an_interleaved_message_breaks_the_pair() {
      Message assistant = Message.assistant(List.of(new ToolUseBlock(call("c1"))));
      Message interleaved = Message.user("wait, one more thing");
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));

      assertThatThrownBy(() -> Context.of(List.of(assistant, interleaved, results)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c1");
    }

    @Test
    void an_empty_context_is_valid() {
      Context context = Context.of(List.of());

      assertThat(context.messages()).isEmpty();
    }
  }

  @Nested
  class The_pair_safe_cut {

    @Test
    void the_cut_lands_exactly_on_the_keep_recent_boundary_when_it_qualifies_there() {
      // 10 plain-text messages; keepRecentMessages=6 puts the limit at index 4 (u3), which
      // qualifies on the very first check — no walk-down needed.
      Context context =
          Context.of(
              List.of(
                  Message.user("u1"), assistantText("a1"),
                  Message.user("u2"), assistantText("a2"),
                  Message.user("u3"), assistantText("a3"),
                  Message.user("u4"), assistantText("a4"),
                  Message.user("u5"), assistantText("a5")));

      assertThat(context.pairSafeCut(6)).isEqualTo(4);
    }

    @Test
    void the_cut_walks_down_past_a_tool_exchange() {
      // 8 messages; keepRecentMessages=3 puts the naive limit at index 5, the tool-result
      // message. That and the tool_use message above it are not genuine user turns, so the
      // walk continues down to index 2 (u2), the nearest genuine user turn.
      Message toolUse = Message.assistant(List.of(new ToolUseBlock(call("c1"))));
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Context context =
          Context.of(
              List.of(
                  Message.user("u1"),
                  assistantText("a1"),
                  Message.user("u2"),
                  assistantText("a2"),
                  toolUse,
                  results,
                  Message.user("u3"),
                  assistantText("a3")));

      assertThat(context.pairSafeCut(3)).isEqualTo(2);
    }

    @Test
    void zero_when_nothing_qualifies() {
      Message toolUse = Message.assistant(List.of(new ToolUseBlock(call("c1"))));
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Context context = Context.of(List.of(toolUse, results));

      assertThat(context.pairSafeCut(0)).isZero();
    }

    @Test
    void keep_recent_messages_of_zero_clamps_to_size_minus_one() {
      // Without the clamp, the naive limit (size - 0 = 2) would index past the end. Clamped to
      // size - 1 = 1, which is the genuine user turn at index 1.
      Context context = Context.of(List.of(assistantText("a0"), Message.user("u1")));

      assertThat(context.pairSafeCut(0)).isEqualTo(1);
    }
  }

  @Nested
  class Head {

    @Test
    void head_returns_the_prefix_before_cut() {
      Message first = Message.user("u1");
      Message second = assistantText("a1");
      Context context = Context.of(List.of(first, second, Message.user("u2")));

      Context head = context.head(context.pairSafeCut(1));

      assertThat(head.messages()).containsExactly(first, second);
    }
  }
}
