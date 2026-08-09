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
package org.jwcarman.nessy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ContentBlock;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ToolResultBlock;

class ContextBuilderTest {

  @Nested
  class Identity {

    @Test
    void identity_projects_every_message_unchanged() {
      SessionState state =
          SessionState.newSession(new SessionId("s1"))
              .withMessages(List.of(Message.user("hi"), Message.user("there")));

      List<Message> projected = ContextBuilder.identity().project(state);

      assertThat(projected).isEqualTo(state.messages());
    }
  }

  @Nested
  class Eliding_tool_results {

    @Test
    void old_tool_results_are_elided_but_their_ids_and_pairing_survive() {
      Message old_result =
          Message.toolResults(
              List.of(
                  new ToolResultBlock("call-1", "forty-two", false),
                  new ToolResultBlock("call-2", "boom", true)));
      Message middle = Message.user("in between");
      Message recent_result =
          Message.toolResults(List.of(new ToolResultBlock("call-3", "still fresh", false)));
      SessionState state =
          SessionState.newSession(new SessionId("s1"))
              .withMessages(List.of(old_result, middle, recent_result));

      List<Message> projected = ContextBuilder.elidingToolResults(1).project(state);

      List<Message> expected =
          List.of(
              Message.toolResults(
                  List.of(
                      new ToolResultBlock("call-1", "[elided]", false),
                      new ToolResultBlock("call-2", "[elided]", true))),
              middle,
              recent_result);
      assertThat(projected).containsExactlyElementsOf(expected);
    }

    @Test
    void recent_messages_are_verbatim() {
      Message old_result =
          Message.toolResults(List.of(new ToolResultBlock("call-1", "old", false)));
      Message recent_1 = Message.user("recent one");
      Message recent_2 =
          Message.toolResults(List.of(new ToolResultBlock("call-2", "recent", false)));
      SessionState state =
          SessionState.newSession(new SessionId("s1"))
              .withMessages(List.of(old_result, recent_1, recent_2));

      List<Message> projected = ContextBuilder.elidingToolResults(2).project(state);

      assertThat(projected.get(1)).isSameAs(recent_1);
      assertThat(projected.get(2)).isSameAs(recent_2);
    }

    @Test
    void non_tool_blocks_are_never_touched() {
      TextBlock untouched_text = new TextBlock("keep me exactly as I am");
      Message old_mixed =
          Message.user(List.of(untouched_text, new ToolResultBlock("call-1", "gone", false)));
      Message recent = Message.user("recent");
      SessionState state =
          SessionState.newSession(new SessionId("s1")).withMessages(List.of(old_mixed, recent));

      List<Message> projected = ContextBuilder.elidingToolResults(1).project(state);

      ContentBlock preserved = projected.get(0).content().get(0);
      assertThat(preserved).isSameAs(untouched_text);
    }

    @Test
    void keep_zero_elides_everything_and_keep_huge_elides_nothing() {
      Message first = Message.toolResults(List.of(new ToolResultBlock("call-1", "one", false)));
      Message second = Message.toolResults(List.of(new ToolResultBlock("call-2", "two", false)));
      SessionState state =
          SessionState.newSession(new SessionId("s1")).withMessages(List.of(first, second));

      List<Message> elides_everything = ContextBuilder.elidingToolResults(0).project(state);
      List<Message> elides_nothing = ContextBuilder.elidingToolResults(100).project(state);

      assertThat(elides_everything)
          .containsExactly(
              Message.toolResults(List.of(new ToolResultBlock("call-1", "[elided]", false))),
              Message.toolResults(List.of(new ToolResultBlock("call-2", "[elided]", false))));
      assertThat(elides_nothing).containsExactly(first, second);
      assertThat(elides_nothing.get(0)).isSameAs(first);
      assertThat(elides_nothing.get(1)).isSameAs(second);
    }

    @Test
    void keep_recent_messages_must_not_be_negative() {
      assertThatThrownBy(() -> ContextBuilder.elidingToolResults(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("keepRecentMessages must be at least 0");
    }
  }
}
