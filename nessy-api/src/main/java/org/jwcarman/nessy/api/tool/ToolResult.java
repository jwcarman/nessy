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
import java.util.Objects;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.message.ResultBlock;
import org.jwcarman.nessy.api.message.TextBlock;

/**
 * What a tool handed back, and whether it went wrong.
 *
 * <p>Content is a list of {@link ResultBlock} rather than a string because a tool can legitimately
 * return an image — MCP's {@code CallToolResult} content is an array, and Anthropic's {@code
 * tool_result} accepts image blocks. Flattening that to text loses the image silently, which is the
 * wrong answer for a screenshot tool or a chart renderer.
 *
 * <p>It is {@code ResultBlock} and not {@code ContentBlock} because a thinking block, a tool-use
 * block, and a nested tool result are all illegal here for every provider. The narrower type makes
 * them unrepresentable instead of leaving a validation rule to be written, tested, and eventually
 * forgotten.
 *
 * <p>Most tools return text, and {@link #ok(String)} / {@link #error(String)} keep that a
 * one-liner.
 *
 * @param content what the tool produced, never null, never containing null
 * @param isError whether this represents a failure the model should see and react to
 */
public record ToolResult(List<ResultBlock> content, boolean isError) {

  public ToolResult {
    Objects.requireNonNull(content, "content must not be null");
    content = List.copyOf(content);
  }

  /** A successful text result — the common case. */
  public static ToolResult ok(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return new ToolResult(List.of(new TextBlock(text)), false);
  }

  /** A failed text result — the common case. */
  public static ToolResult error(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return new ToolResult(List.of(new TextBlock(text)), true);
  }

  /** A successful result carrying arbitrary legal content. */
  public static ToolResult ok(List<ResultBlock> content) {
    return new ToolResult(content, false);
  }

  /** A failed result carrying arbitrary legal content. */
  public static ToolResult error(List<ResultBlock> content) {
    return new ToolResult(content, true);
  }

  /**
   * The text of this result, with any non-text content dropped.
   *
   * <p>For logs, span attributes, and assertions — the places that want a line of prose rather than
   * a structure. <b>Not</b> for building a provider request: a provider must render the blocks, or
   * an image a tool returned vanishes on its way to the model.
   */
  public String text() {
    return content.stream()
        .filter(TextBlock.class::isInstance)
        .map(block -> ((TextBlock) block).text())
        .collect(Collectors.joining("\n"));
  }
}
