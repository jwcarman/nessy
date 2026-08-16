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
import org.jwcarman.nessy.examples.newsroom.AskQuestionTool.AskQuestion;

class AskQuestionToolTest {

  @Test
  void execute_returns_whatever_answer_was_recorded_for_this_call() {
    PendingAnswers answers = new PendingAnswers();
    answers.record("call-1", "the harbor story, not the market one");
    AskQuestionTool tool = new AskQuestionTool(answers);
    ToolCall call = new ToolCall("call-1", "ask_question", JsonNodeFactory.instance.objectNode());
    ToolContext context = new ToolContext(new ConversationId("conversation-1"), call, event -> {});

    Awaited<ToolResult> awaited = tool.execute(new AskQuestion("which story?"), context);

    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
    assertThat(result.isError()).isFalse();
    assertThat(result.content()).isEqualTo("the harbor story, not the market one");
  }

  @Test
  void execute_fails_loud_when_no_answer_was_recorded_yet() {
    AskQuestionTool tool = new AskQuestionTool(new PendingAnswers());
    ToolCall call = new ToolCall("call-1", "ask_question", JsonNodeFactory.instance.objectNode());
    ToolContext context = new ToolContext(new ConversationId("conversation-1"), call, event -> {});

    assertThatThrownBy(() -> tool.execute(new AskQuestion("which story?"), context))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void a_blank_question_is_rejected_before_the_tool_ever_runs() {
    assertThatThrownBy(() -> new AskQuestion(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void describe_renders_the_question_itself_for_the_approval_prompt() {
    AskQuestionTool tool = new AskQuestionTool(new PendingAnswers());

    assertThat(tool.describe(new AskQuestion("which story?"))).isEqualTo("which story?");
  }
}
