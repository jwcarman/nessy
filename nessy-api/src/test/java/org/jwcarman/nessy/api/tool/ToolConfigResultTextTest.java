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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;

@DisplayName("The default text of a tool result")
class ToolConfigResultTextTest {

  private static String textOf(List<Block.ToolResultContent> blocks) {
    return ToolConfig.resultText().stringify(new ToolResult.Success(blocks));
  }

  @Test
  void a_result_with_no_blocks_has_no_text() {
    assertThat(textOf(List.of())).isEmpty();
  }

  @Test
  void a_result_with_one_text_block_is_that_text() {
    assertThat(textOf(List.of(new Block.Text("shipped")))).isEqualTo("shipped");
  }

  @Test
  void a_result_with_two_text_blocks_joins_them_by_one_space() {
    assertThat(textOf(List.of(new Block.Text("one"), new Block.Text("two")))).isEqualTo("one two");
  }
}
