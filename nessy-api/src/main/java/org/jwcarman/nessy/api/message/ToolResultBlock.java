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

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A tool's answer, in the transcript, paired to the {@link ToolUseBlock} that asked for it.
 *
 * <p>Content is a list of {@link ResultBlock} for the same reason {@code ToolResult}'s is: this is
 * the block that actually crosses the wire to a provider, so leaving it text-only would flatten a
 * tool's image one layer below where the widening happened — the change would look done and not be.
 *
 * @param toolUseId the id of the {@link ToolUseBlock} this answers
 * @param content what the tool produced, never null, never containing null
 * @param isError whether the tool failed
 */
public record ToolResultBlock(String toolUseId, List<ResultBlock> content, boolean isError)
    implements ContentBlock {

  public ToolResultBlock {
    Objects.requireNonNull(toolUseId, "toolUseId must not be null");
    Objects.requireNonNull(content, "content must not be null");
    content = List.copyOf(content);
  }

  /** The common case: a text answer. */
  public static ToolResultBlock of(String toolUseId, String text, boolean isError) {
    Objects.requireNonNull(text, "text must not be null");
    return new ToolResultBlock(toolUseId, List.of(new TextBlock(text)), isError);
  }

  /**
   * The text of this block, with any non-text content dropped — for logs and assertions, never for
   * building a provider request.
   */
  public String text() {
    return content.stream()
        .filter(TextBlock.class::isInstance)
        .map(block -> ((TextBlock) block).text())
        .collect(Collectors.joining("\n"));
  }
}
