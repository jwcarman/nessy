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
package org.jwcarman.nessy.examples.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Pinned without Spring, no Docker: {@link TranscriptView} is a pure function over {@link Context}.
 * Chat-web's own {@code TranscriptViewTest} is the template; this local copy proves this module's
 * local copy of the class behaves identically, including the branch the review flagged — a message
 * that carries no {@link TextBlock} at all (a pure tool-use/tool-result exchange) contributes
 * nothing rather than an empty {@link TranscriptView.Line}.
 */
class TranscriptViewTest {

  @Test
  void joins_text_blocks_per_message_and_drops_messages_with_no_text() {
    ToolCall call = new ToolCall("c1", "request_field_crew", JsonNodeFactory.instance.objectNode());
    Context context =
        Context.of(
            List.of(
                Message.user("hi"),
                Message.assistant(List.of(new TextBlock("hello"), new ToolUseBlock(call))),
                Message.toolResults(List.of(new ToolResultBlock(call.id(), "done", false)))));

    List<TranscriptView.Line> lines = TranscriptView.of(context);

    assertThat(lines)
        .isNotEmpty()
        .containsExactly(
            new TranscriptView.Line("user", "hi"), new TranscriptView.Line("assistant", "hello"));
  }

  @Test
  void a_message_with_no_text_blocks_contributes_no_line() {
    ToolCall call = new ToolCall("c1", "request_field_crew", JsonNodeFactory.instance.objectNode());
    Context context =
        Context.of(
            List.of(
                Message.assistant(List.of(new ToolUseBlock(call))),
                Message.toolResults(List.of(new ToolResultBlock(call.id(), "done", false)))));

    List<TranscriptView.Line> lines = TranscriptView.of(context);

    assertThat(lines).isEmpty();
  }

  @Test
  void an_empty_context_renders_no_lines() {
    List<TranscriptView.Line> lines = TranscriptView.of(Context.of(List.of()));

    assertThat(lines).isEmpty();
  }
}
