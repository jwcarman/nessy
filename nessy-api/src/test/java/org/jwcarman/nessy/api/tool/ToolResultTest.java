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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolResultContentBlock;

@DisplayName("What a tool call produced")
class ToolResultTest {

  @Nested
  @DisplayName("a success")
  class ASuccess {

    @Test
    @DisplayName("ok(String) wraps the text as a single content block")
    void ok_text_wraps_a_single_block() {
      ToolResult result = ToolResult.ok("done");

      assertThat(result).isInstanceOf(ToolResult.Success.class);
      assertThat(((ToolResult.Success) result).content()).containsExactly(new TextBlock("done"));
    }

    @Test
    @DisplayName("ok(List) carries the content exactly as given")
    void ok_list_carries_the_given_content() {
      List<ToolResultContentBlock> content = List.of(new TextBlock("a"), new TextBlock("b"));

      ToolResult result = ToolResult.ok(content);

      assertThat(result).isInstanceOf(ToolResult.Success.class);
      assertThat(((ToolResult.Success) result).content()).containsExactlyElementsOf(content);
    }
  }

  @Nested
  @DisplayName("a failure")
  class AFailure {

    @Test
    @DisplayName("error(String) carries the explanation the model reads to judge a retry")
    void error_carries_the_message() {
      ToolResult result = ToolResult.error("could not reach the host");

      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message()).isEqualTo("could not reach the host");
    }

    @Test
    @DisplayName("refuses a null message, since the model has nothing to act on otherwise")
    void refuses_a_null_message() {
      assertThatThrownBy(() -> new ToolResult.Failure(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("message must not be null");
    }
  }
}
