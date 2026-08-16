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
package org.jwcarman.nessy.examples.newsroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.examples.newsroom.SearchNotesTool.SearchNotes;

class SearchNotesToolTest {

  private final SearchNotesTool tool = new SearchNotesTool();

  @Test
  void a_known_topic_returns_its_canned_note() {
    ToolResult result = execute(new SearchNotes("octopus"));

    assertThat(result.isError()).isFalse();
    assertThat(result.content()).contains("three hearts");
  }

  @Test
  void a_topic_lookup_is_case_and_whitespace_insensitive() {
    ToolResult result = execute(new SearchNotes("  Coffee "));

    assertThat(result.isError()).isFalse();
    assertThat(result.content()).contains("Finland");
  }

  @Test
  void an_unknown_topic_errors_rather_than_fabricating_a_note() {
    ToolResult result = execute(new SearchNotes("blockchain"));

    assertThat(result.isError()).isTrue();
    assertThat(result.content()).contains("blockchain");
  }

  @Test
  void a_blank_topic_is_rejected_before_the_tool_ever_runs() {
    assertThatThrownBy(() -> new SearchNotes(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  private ToolResult execute(SearchNotes input) {
    ToolCall call = new ToolCall("call-1", "search_notes", JsonNodeFactory.instance.objectNode());
    ToolContext context = new ToolContext(new ConversationId("conversation-1"), call, event -> {});
    Awaited<ToolResult> awaited = tool.execute(input, context);
    return switch (awaited) {
      case Awaited.Ready<ToolResult> ready -> ready.value();
      case Awaited.Parked<ToolResult> _ ->
          throw new AssertionError("search_notes should never park");
    };
  }
}
