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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;

class ContextLinesTest {

  @Test
  void joins_text_blocks_per_message_and_drops_messages_with_no_text() {
    ToolCall call = new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
    Context context =
        Context.of(
            List.of(
                Message.user("hi"),
                Message.assistant(
                    List.of(
                        new ThinkingBlock("pondering", "sig"),
                        new TextBlock("hello"),
                        new ToolUseBlock(call))),
                Message.toolResults(List.of(ToolResultBlock.of(call.id(), "done", false)))));

    List<Context.Line> lines = context.lines();

    assertThat(lines)
        .isNotEmpty()
        .containsExactly(new Context.Line("user", "hi"), new Context.Line("assistant", "hello"));
  }

  @Test
  void a_message_with_no_text_blocks_contributes_no_line() {
    ToolCall call = new ToolCall("c1", "request_field_crew", JsonNodeFactory.instance.objectNode());
    Context context =
        Context.of(
            List.of(
                Message.assistant(List.of(new ToolUseBlock(call))),
                Message.toolResults(List.of(ToolResultBlock.of(call.id(), "done", false)))));

    List<Context.Line> lines = context.lines();

    assertThat(lines).isEmpty();
  }

  @Test
  void an_empty_context_renders_no_lines() {
    List<Context.Line> lines = Context.of(List.of()).lines();

    assertThat(lines).isEmpty();
  }
}
