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

import java.util.List;
import org.jwcarman.nessy.api.block.Block;

public sealed interface ToolResult {

  record Success(List<Block.ToolResultContent> blocks) implements ToolResult {}

  record Failure(String message) implements ToolResult {}

  static ToolResult ok(Block.ToolResultContent block) {
    return ok(List.of(block));
  }

  static ToolResult ok(List<Block.ToolResultContent> blocks) {
    return new Success(blocks);
  }
}
