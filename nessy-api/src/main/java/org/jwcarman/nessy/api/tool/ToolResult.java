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
