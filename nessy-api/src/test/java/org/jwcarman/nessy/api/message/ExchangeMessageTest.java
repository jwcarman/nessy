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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The pairing rule, in the one place it still exists.
 *
 * <p>This used to be enforced three times — when a context was built, when the pair was remembered,
 * and by hand in the engine while the calls settled. It is enforced here now, once, at the moment
 * the value comes into being, which is why nothing downstream has to check it again.
 */
@DisplayName("An exchange with the model")
class ExchangeMessageTest {

  private static ToolCallBlock calling(String id) {
    return new ToolCallBlock(
        new ToolCall(CallId.of(id), "read_file", JsonNodeFactory.instance.objectNode()));
  }

  private static ToolResultBlock answering(String id) {
    return ToolResultBlock.of(CallId.of(id), ToolResult.ok("ok"));
  }

  @Test
  void carries_its_calls_and_their_answers() {
    ExchangeMessage exchange = new ExchangeMessage(List.of(calling("a")), List.of(answering("a")));

    assertThat(exchange.calls()).hasSize(1);
    assertThat(exchange.results()).hasSize(1);
  }

  @Test
  @DisplayName("commentary rides along, because the model said it while working")
  void carries_the_commentary_that_came_with_the_calls() {
    ExchangeMessage exchange =
        new ExchangeMessage(
            List.of(new CommentaryBlock("I'll look that up"), calling("a")),
            List.of(answering("a")));

    assertThat(exchange.content()).hasSize(2);
    assertThat(exchange.calls()).hasSize(1);
  }

  @Test
  void refuses_a_call_nobody_answered() {
    List<ExchangeContentBlock> content = List.of(calling("a"), calling("b"));
    List<ToolResultBlock> results = List.of(answering("a"));

    assertThatThrownBy(() -> new ExchangeMessage(content, results))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unanswered tool call: b");
  }

  @Test
  void refuses_an_answer_to_a_call_that_was_never_made() {
    List<ExchangeContentBlock> content = List.of(calling("a"));
    List<ToolResultBlock> results = List.of(answering("a"), answering("ghost"));

    assertThatThrownBy(() -> new ExchangeMessage(content, results))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("answering no call: ghost");
  }

  @Test
  @DisplayName("an exchange that asks for nothing is not an exchange")
  void refuses_content_with_no_calls_at_all() {
    List<ExchangeContentBlock> content = List.of(new CommentaryBlock("just talking"));
    List<ToolResultBlock> results = List.of();

    assertThatThrownBy(() -> new ExchangeMessage(content, results))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
