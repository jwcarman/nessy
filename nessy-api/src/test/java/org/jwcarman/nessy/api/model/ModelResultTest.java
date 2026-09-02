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
package org.jwcarman.nessy.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * What a model call came back as.
 *
 * <p>Sealed into three arms so a caller checks WHY the turn stopped before reading content — a
 * single record with a nullable message would let an answer be read off a refusal.
 */
@DisplayName("What the model said")
class ModelResultTest {

  private static final Usage NOTHING_MEASURED = new Usage(null, null, null, null);

  private static ToolCallBlock call(String id) {
    return new ToolCallBlock(
        new ToolCall(CallId.of(id), "look_up", JsonNodeFactory.instance.objectNode()));
  }

  @Nested
  class Answering {

    @Test
    void an_answer_carries_why_it_stopped() {
      var answered =
          new ModelResult.Answered(
              new AnswerMessage(List.of(new TextBlock("hello"))),
              StopReason.MAX_TOKENS,
              NOTHING_MEASURED);

      assertThat(answered.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
      assertThat(answered.message().content()).hasSize(1);
    }

    @Test
    @DisplayName("an answer that ran out of room is not the same as one that finished")
    void the_stop_reason_is_required() {
      var message = new AnswerMessage(List.of(new TextBlock("hello")));

      assertThatThrownBy(() -> new ModelResult.Answered(message, null, NOTHING_MEASURED))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("stopReason");
    }
  }

  @Nested
  class AskingForTools {

    @Test
    void it_carries_the_calls_and_whatever_was_said_while_making_them() {
      var asked =
          new ModelResult.Asked(
              List.of(new CommentaryBlock("let me look"), call("c1")), NOTHING_MEASURED);

      assertThat(asked.content()).hasSize(2);
    }

    @Test
    @DisplayName("asking for no tools is not asking — the engine would have nothing to run")
    void content_without_a_tool_call_is_refused() {
      List<ExchangeContentBlock> nothingToRun = List.of(new CommentaryBlock("thinking"));

      assertThatThrownBy(() -> new ModelResult.Asked(nothingToRun, NOTHING_MEASURED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("asked for nothing");
    }

    @Test
    void an_empty_request_is_refused_for_the_same_reason() {
      List<ExchangeContentBlock> empty = List.of();

      assertThatThrownBy(() -> new ModelResult.Asked(empty, NOTHING_MEASURED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("asked for nothing");
    }

    @Test
    @DisplayName("the content is copied, so a caller's list cannot change it afterwards")
    void the_content_is_defensively_copied() {
      var mutable = new java.util.ArrayList<ExchangeContentBlock>(List.of(call("c1")));
      var asked = new ModelResult.Asked(mutable, NOTHING_MEASURED);

      mutable.clear();

      assertThat(asked.content()).hasSize(1);
    }
  }

  @Nested
  class Refusing {

    @Test
    void a_refusal_carries_the_provider_word_for_it() {
      var refused = new ModelResult.Refused("safety", "not going to do that", NOTHING_MEASURED);

      assertThat(refused.category()).isEqualTo("safety");
      assertThat(refused.explanation()).isEqualTo("not going to do that");
    }

    @Test
    @DisplayName("a provider that said nothing says an empty string, never null")
    void an_absent_explanation_is_refused() {
      assertThatThrownBy(() -> new ModelResult.Refused("safety", null, NOTHING_MEASURED))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("explanation");
    }
  }
}
