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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
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

  /** An exchange that called {@code callIds} and got an answer for each. */
  private static ExchangeMessage exchange(String... callIds) {
    List<ExchangeContentBlock> blocks =
        java.util.Arrays.stream(callIds)
            .map(id -> (ExchangeContentBlock) new ToolCallBlock(call(id)))
            .toList();
    return new ExchangeMessage(
        blocks,
        java.util.Arrays.stream(callIds)
            .map(id -> ToolResultBlock.of(id, ToolResult.ok("ok")))
            .toList());
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

    /**
     * There is no way to drop half an exchange, because there are no halves: the calls and their
     * answers are one value. This used to take two tests and a two-at-a-time walk through the list
     * to guarantee.
     */
    @Test
    void dropping_an_exchange_takes_its_answers_with_it() {
      Context context = Context.of(List.of(user("go"), exchange("a")));

      Context result = context.drop(ExchangeMessage.class::isInstance);

      assertThat(result.messages()).containsExactly(user("go"));
    }

    @Test
    void dropping_everything_leaves_a_valid_empty_context() {
      Context context = Context.of(List.of(user("go"), exchange("a")));

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
      Context context = Context.of(List.of(exchange("a"), user("later"), assistantText("done")));

      Context result = context.elideToolResults(2);
      ExchangeMessage elided = (ExchangeMessage) result.messages().getFirst();

      assertThat(elided.results()).isNotEmpty();
      assertThat(elided.results())
          .allSatisfy(
              block -> {
                assertThat(block.toolUseId()).isEqualTo("a");
                assertThat(block.content()).containsExactly(new TextBlock("[elided]"));
              });
    }

    @Test
    void leaves_the_recent_window_verbatim() {
      Context context = Context.of(List.of(exchange("a")));

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

    /**
     * Every boundary is safe now. Keeping none keeps none — where this once had to return the whole
     * context untouched, because cutting anywhere risked landing between a call and its answer.
     */
    @Test
    void keeping_none_keeps_none() {
      Context context = Context.of(List.of(exchange("a")));

      Context result = context.keepRecent(0);

      assertThat(result.messages()).isEmpty();
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
      Context context = Context.of(List.of(exchange("a")));

      assertThat(context.lines()).isEmpty();
    }
  }
}
