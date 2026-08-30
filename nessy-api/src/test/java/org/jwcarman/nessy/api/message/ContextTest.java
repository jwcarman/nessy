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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.AssistantContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class ContextTest {

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  private static UserMessage user(String text) {
    return UserMessage.of(text);
  }

  private static AssistantMessage assistantText(String text) {
    return new AssistantMessage(List.of(new TextBlock(text)));
  }

  private static AssistantMessage assistantCalling(String... callIds) {
    List<AssistantContentBlock> blocks =
        java.util.Arrays.stream(callIds)
            .map(id -> (AssistantContentBlock) new ToolCallBlock(call(id)))
            .toList();
    return new AssistantMessage(blocks);
  }

  private static ToolResultMessage answering(String... callIds) {
    return new ToolResultMessage(
        java.util.Arrays.stream(callIds)
            .map(id -> ToolResultBlock.of(id, ToolResult.ok("ok")))
            .toList());
  }

  @Nested
  class Validity {

    @Test
    void a_plain_conversation_is_valid() {
      Context context = Context.of(List.of(user("hi"), assistantText("hello")));

      assertThat(context.messages()).hasSize(2);
    }

    @Test
    void empty_is_legal_and_has_no_messages() {
      assertThat(Context.empty().messages()).isEmpty();
    }

    @Test
    void a_completed_tool_exchange_is_valid() {
      Context context =
          Context.of(
              List.of(user("go"), assistantCalling("a"), answering("a"), assistantText("done")));

      assertThat(context.messages()).hasSize(4);
    }

    @Test
    void parallel_calls_answered_together_are_valid() {
      Context context =
          Context.of(List.of(user("go"), assistantCalling("a", "b"), answering("a", "b")));

      assertThat(context.messages()).hasSize(3);
    }

    @Test
    void a_trailing_unanswered_call_is_refused() {
      List<Message> messages = List.of(user("go"), assistantCalling("a"));

      assertThatThrownBy(() -> Context.of(messages))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unanswered tool call: a");
    }

    @Test
    void a_call_answered_by_something_other_than_results_is_refused() {
      List<Message> messages = List.of(assistantCalling("a"), user("never mind"));

      assertThatThrownBy(() -> Context.of(messages))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unanswered tool call: a");
    }

    @Test
    void a_partially_answered_set_of_calls_is_refused() {
      List<Message> messages = List.of(assistantCalling("a", "b"), answering("a"));

      assertThatThrownBy(() -> Context.of(messages))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unanswered tool call: b");
    }

    @Test
    void an_answer_naming_an_unknown_call_is_refused() {
      List<Message> messages = List.of(assistantCalling("a"), answering("a", "z"));

      assertThatThrownBy(() -> Context.of(messages))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown id: z");
    }

    @Test
    void results_answering_nothing_are_refused() {
      List<Message> messages = List.of(user("hi"), answering("a"));

      assertThatThrownBy(() -> Context.of(messages))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("answering no call");
    }
  }

  @Nested
  class Drop {

    @Test
    void drops_a_plain_message_on_its_own() {
      Context context = Context.of(List.of(user("keep"), user("lose"), assistantText("hi")));

      Context result =
          context.drop(
              m -> m instanceof UserMessage u && u.content().contains(new TextBlock("lose")));

      assertThat(result.messages()).hasSize(2);
    }

    @Test
    void dropping_the_calling_half_takes_the_answering_half_too() {
      Context context = Context.of(List.of(user("go"), assistantCalling("a"), answering("a")));

      Context result = context.drop(AssistantMessage.class::isInstance);

      assertThat(result.messages()).containsExactly(user("go"));
    }

    @Test
    void dropping_the_answering_half_takes_the_calling_half_too() {
      Context context = Context.of(List.of(user("go"), assistantCalling("a"), answering("a")));

      Context result = context.drop(ToolResultMessage.class::isInstance);

      assertThat(result.messages()).containsExactly(user("go"));
    }

    @Test
    void dropping_everything_leaves_a_valid_empty_context() {
      Context context = Context.of(List.of(user("go"), assistantCalling("a"), answering("a")));

      Context result = context.drop(m -> true);

      assertThat(result.messages()).isEmpty();
    }
  }

  @Nested
  class Map {

    @Test
    void rewrites_every_message_once_in_order() {
      Context context = Context.of(List.of(user("a"), assistantText("b")));

      Context result = context.map(m -> m instanceof UserMessage ? user("rewritten") : m);

      assertThat(result.messages()).first().isEqualTo(user("rewritten"));
    }

    @Test
    void a_rewrite_that_breaks_pairing_propagates_the_failure() {
      Context context = Context.of(List.of(assistantCalling("a"), answering("a")));
      UnaryOperator<Message> renamer =
          m -> m instanceof ToolResultMessage ? answering("different") : m;

      assertThatThrownBy(() -> context.map(renamer))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unanswered tool call: a");
    }
  }

  @Nested
  class Enrich {

    @Test
    void appends_exactly_one_user_message() {
      Context context = Context.of(List.of(user("hi")));

      Context result = context.enrich(new TextBlock("and also this"));

      assertThat(result.messages()).hasSize(2).last().isInstanceOf(UserMessage.class);
    }

    @Test
    void enriching_with_nothing_is_a_caller_bug() {
      Context context = Context.empty();
      List<org.jwcarman.nessy.api.block.UserContentBlock> nothing = List.of();

      assertThatThrownBy(() -> context.enrich(nothing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be empty");
    }
  }

  @Nested
  class ElideToolResults {

    @Test
    void replaces_old_result_content_and_keeps_ids_and_error_flags() {
      Context context =
          Context.of(
              List.of(assistantCalling("a"), answering("a"), user("later"), assistantText("done")));

      Context result = context.elideToolResults(2);
      ToolResultMessage elided = (ToolResultMessage) result.messages().get(1);

      assertThat(elided.blocks()).isNotEmpty();
      assertThat(elided.blocks())
          .allSatisfy(
              block -> {
                assertThat(block.toolUseId()).isEqualTo("a");
                assertThat(block.content()).containsExactly(new TextBlock("[elided]"));
              });
    }

    @Test
    void leaves_the_recent_window_verbatim() {
      Context context = Context.of(List.of(assistantCalling("a"), answering("a")));

      Context result = context.elideToolResults(2);

      assertThat(result.messages()).isEqualTo(context.messages());
    }

    @Test
    void a_negative_window_is_a_caller_bug() {
      Context context = Context.empty();

      assertThatThrownBy(() -> context.elideToolResults(-1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class KeepRecent {

    @Test
    void cuts_at_a_genuine_user_turn() {
      Context context =
          Context.of(List.of(user("one"), assistantText("a"), user("two"), assistantText("b")));

      Context result = context.keepRecent(2);

      assertThat(result.messages()).containsExactly(user("two"), assistantText("b"));
    }

    @Test
    void returns_itself_when_no_boundary_is_safe() {
      Context context = Context.of(List.of(assistantCalling("a"), answering("a")));

      Context result = context.keepRecent(0);

      assertThat(result.messages()).isEqualTo(context.messages());
    }

    @Test
    void never_cuts_between_a_call_and_its_answer() {
      Context context =
          Context.of(List.of(user("one"), assistantCalling("a"), answering("a"), user("two")));

      int cut = context.pairSafeCut(1);

      assertThat(context.messages().get(cut)).isInstanceOf(UserMessage.class);
    }
  }

  @Nested
  class Lines {

    @Test
    void renders_prose_with_the_speaker() {
      Context context = Context.of(List.of(user("hi"), assistantText("hello")));

      assertThat(context.lines())
          .containsExactly(new Context.Line("user", "hi"), new Context.Line("assistant", "hello"));
    }

    @Test
    void a_message_with_no_prose_contributes_nothing() {
      Context context = Context.of(List.of(assistantCalling("a"), answering("a")));

      assertThat(context.lines()).isEmpty();
    }
  }
}
