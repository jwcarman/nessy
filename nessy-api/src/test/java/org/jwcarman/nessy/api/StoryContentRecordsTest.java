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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.IdempotencyKey;

@DisplayName("The records a story's content is read into")
class StoryContentRecordsTest {

  private static final IdempotencyKey KEY = IdempotencyKey.of(UUID.randomUUID());

  @Nested
  @DisplayName("A call's result")
  class ACallsResult {

    @Test
    void without_a_position_is_refused() {
      List<Block.ToolResultContent> blocks = List.of();

      assertThatThrownBy(() -> new CallResult(null, KEY, blocks))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("seq must not be null");
    }

    @Test
    void without_a_key_is_refused() {
      List<Block.ToolResultContent> blocks = List.of();

      assertThatThrownBy(() -> new CallResult(new Seq(1), null, blocks))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("idempotencyKey must not be null");
    }

    @Test
    void without_blocks_is_refused() {
      assertThatThrownBy(() -> new CallResult(new Seq(1), KEY, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("blocks must not be null");
    }

    @Test
    void keeps_its_own_copy_of_the_blocks() {
      List<Block.ToolResultContent> blocks = new ArrayList<>();
      CallResult result = new CallResult(new Seq(1), KEY, blocks);
      blocks.add(new Block.Text("late"));

      assertThat(result.blocks()).isEmpty();
    }
  }

  @Nested
  @DisplayName("A request's content")
  class ARequestsContent {

    @Test
    void without_a_position_is_refused() {
      List<Block.ActionRequestContent> blocks = List.of();

      assertThatThrownBy(() -> new RequestContent(null, blocks))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("seq must not be null");
    }

    @Test
    void without_blocks_is_refused() {
      assertThatThrownBy(() -> new RequestContent(new Seq(1), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("blocks must not be null");
    }
  }

  @Nested
  @DisplayName("A turn's content")
  class ATurnsContent {

    @Test
    void without_input_is_refused() {
      List<RequestContent> requests = List.of();
      Optional<List<Block.AnswerContent>> answer = Optional.empty();

      assertThatThrownBy(() -> new TurnContent(null, requests, answer))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("input must not be null");
    }

    @Test
    void without_requests_is_refused() {
      List<Block.InputContent> input = List.of();
      Optional<List<Block.AnswerContent>> answer = Optional.empty();

      assertThatThrownBy(() -> new TurnContent(input, null, answer))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("requests must not be null");
    }

    @Test
    void without_an_answer_optional_is_refused() {
      List<Block.InputContent> input = List.of();
      List<RequestContent> requests = List.of();

      assertThatThrownBy(() -> new TurnContent(input, requests, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("answer must not be null");
    }
  }
}
