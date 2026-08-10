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
package org.jwcarman.nessy.spi.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.SessionState;
import org.jwcarman.nessy.api.tool.ToolCall;

class ContextBuilderTest {

  private static Message toolUse(String callId) {
    return Message.assistant(
        List.of(
            new ToolUseBlock(
                new ToolCall(callId, "lookup", JsonNodeFactory.instance.objectNode()))));
  }

  @Nested
  class Identity {

    @Test
    void identity_projects_every_message_unchanged() {
      SessionState state =
          SessionState.newSession(new SessionId("s1"))
              .withMessages(List.of(Message.user("hi"), Message.user("there")));

      Context projected = ContextBuilder.identity().project(state);

      assertThat(projected.messages()).isEqualTo(state.messages());
    }
  }

  @Nested
  class Eliding_tool_results {

    @Test
    void old_tool_results_are_elided_but_their_ids_and_pairing_survive() {
      Message assistant1 =
          Message.assistant(
              List.of(
                  new ToolUseBlock(
                      new ToolCall("call-1", "lookup", JsonNodeFactory.instance.objectNode())),
                  new ToolUseBlock(
                      new ToolCall("call-2", "lookup", JsonNodeFactory.instance.objectNode()))));
      Message old_result =
          Message.toolResults(
              List.of(
                  new ToolResultBlock("call-1", "forty-two", false),
                  new ToolResultBlock("call-2", "boom", true)));
      Message middle = Message.user("in between");
      Message assistant2 = toolUse("call-3");
      Message recent_result =
          Message.toolResults(List.of(new ToolResultBlock("call-3", "still fresh", false)));
      SessionState state =
          SessionState.newSession(new SessionId("s1"))
              .withMessages(List.of(assistant1, old_result, middle, assistant2, recent_result));

      Context projected = ContextBuilder.elidingToolResults(1).project(state);

      List<Message> expected =
          List.of(
              assistant1,
              Message.toolResults(
                  List.of(
                      new ToolResultBlock("call-1", "[elided]", false),
                      new ToolResultBlock("call-2", "[elided]", true))),
              middle,
              assistant2,
              recent_result);
      assertThat(projected.messages()).containsExactlyElementsOf(expected);
    }

    @Test
    void recent_messages_are_verbatim() {
      Message assistant_call1 = toolUse("call-1");
      Message old_result =
          Message.toolResults(List.of(new ToolResultBlock("call-1", "old", false)));
      Message recent_assistant = toolUse("call-2");
      Message recent_result =
          Message.toolResults(List.of(new ToolResultBlock("call-2", "recent", false)));
      SessionState state =
          SessionState.newSession(new SessionId("s1"))
              .withMessages(List.of(assistant_call1, old_result, recent_assistant, recent_result));

      Context projected = ContextBuilder.elidingToolResults(2).project(state);

      assertThat(projected.messages().get(2)).isSameAs(recent_assistant);
      assertThat(projected.messages().get(3)).isSameAs(recent_result);
    }

    @Test
    void non_tool_blocks_are_never_touched() {
      TextBlock untouched_text = new TextBlock("keep me exactly as I am");
      Message assistant1 = toolUse("call-1");
      Message old_mixed =
          Message.user(List.of(untouched_text, new ToolResultBlock("call-1", "gone", false)));
      Message recent = Message.user("recent");
      SessionState state =
          SessionState.newSession(new SessionId("s1"))
              .withMessages(List.of(assistant1, old_mixed, recent));

      Context projected = ContextBuilder.elidingToolResults(1).project(state);

      ContentBlock preserved = projected.messages().get(1).content().get(0);
      assertThat(preserved).isSameAs(untouched_text);
    }

    @Test
    void keep_zero_elides_everything_and_keep_huge_elides_nothing() {
      Message assistant1 = toolUse("call-1");
      Message first = Message.toolResults(List.of(new ToolResultBlock("call-1", "one", false)));
      Message assistant2 = toolUse("call-2");
      Message second = Message.toolResults(List.of(new ToolResultBlock("call-2", "two", false)));
      SessionState state =
          SessionState.newSession(new SessionId("s1"))
              .withMessages(List.of(assistant1, first, assistant2, second));

      Context elides_everything = ContextBuilder.elidingToolResults(0).project(state);
      Context elides_nothing = ContextBuilder.elidingToolResults(100).project(state);

      assertThat(elides_everything.messages())
          .containsExactly(
              assistant1,
              Message.toolResults(List.of(new ToolResultBlock("call-1", "[elided]", false))),
              assistant2,
              Message.toolResults(List.of(new ToolResultBlock("call-2", "[elided]", false))));
      assertThat(elides_nothing.messages()).containsExactly(assistant1, first, assistant2, second);
      assertThat(elides_nothing.messages().get(1)).isSameAs(first);
      assertThat(elides_nothing.messages().get(3)).isSameAs(second);
    }

    @Test
    void keep_recent_messages_must_not_be_negative() {
      assertThatThrownBy(() -> ContextBuilder.elidingToolResults(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("keepRecentMessages must be at least 0");
    }
  }
}
