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

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolResult;

/** What a tool may hand back, and what the type system refuses to let it hand back. */
class ResultBlockTest {

  private static final ImageBlock A_SCREENSHOT = new ImageBlock("image/png", "aGVsbG8=");

  @Nested
  class The_grammar {

    @Test
    void admits_text_and_images_and_nothing_else() {
      assertThat(ResultBlock.class.getPermittedSubclasses())
          .containsExactlyInAnyOrder(TextBlock.class, ImageBlock.class);
    }

    @Test
    void keeps_its_members_usable_as_ordinary_content_blocks() {
      assertThat(ContentBlock.class).isAssignableFrom(TextBlock.class);
      assertThat(ContentBlock.class).isAssignableFrom(ImageBlock.class);
    }

    @Test
    void closes_the_recursion_a_nested_tool_result_would_open() {
      assertThat(ResultBlock.class.getPermittedSubclasses())
          .doesNotContain(ToolResultBlock.class, ToolUseBlock.class, ThinkingBlock.class);
    }
  }

  @Nested
  class A_tool_result {

    @Test
    void carries_an_image_alongside_its_text() {
      ToolResult result = ToolResult.ok(List.of(new TextBlock("here is the chart"), A_SCREENSHOT));

      assertThat(result.content())
          .containsExactly(new TextBlock("here is the chart"), A_SCREENSHOT);
      assertThat(result.isError()).isFalse();
    }

    @Test
    void stays_a_one_liner_for_the_common_text_case() {
      assertThat(ToolResult.ok("done").content()).containsExactly(new TextBlock("done"));
      assertThat(ToolResult.error("boom").isError()).isTrue();
    }

    @Test
    void reports_its_text_for_logs_while_keeping_the_image_in_content() {
      ToolResult result = ToolResult.ok(List.of(new TextBlock("chart follows"), A_SCREENSHOT));

      assertThat(result.text()).isEqualTo("chart follows");
      assertThat(result.content()).hasSize(2);
    }
  }

  @Nested
  class A_tool_result_block {

    @Test
    void widens_alongside_the_result_so_nothing_is_flattened_one_layer_down() {
      ToolResultBlock block =
          new ToolResultBlock("call-1", List.of(new TextBlock("ok"), A_SCREENSHOT), false);

      assertThat(block.content()).containsExactly(new TextBlock("ok"), A_SCREENSHOT);
      assertThat(block.text()).isEqualTo("ok");
    }

    @Test
    void keeps_a_text_shorthand_for_the_common_case() {
      ToolResultBlock block = ToolResultBlock.of("call-1", "done", false);

      assertThat(block.content()).containsExactly(new TextBlock("done"));
      assertThat(block.toolUseId()).isEqualTo("call-1");
    }
  }
}
