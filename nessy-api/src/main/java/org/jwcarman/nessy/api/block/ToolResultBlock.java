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
package org.jwcarman.nessy.api.block;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * A tool's answer, on the wire, paired to the {@link ToolCallBlock} that asked for it.
 *
 * <p><b>Only the engine builds one.</b> It differs from {@code ToolResult} — what a tool returns —
 * by exactly one field, {@code toolUseId}, and that field is the one only the engine knows. A tool
 * therefore cannot echo the wrong id, answer a call it was not asked about, or answer two; the
 * pairing invariant in {@code Context} has no tool-authored identity to defend against, because
 * tool-authored identity does not exist.
 *
 * <p>Carries no marker interface: the only container that accepts it is a tool-result message,
 * which names it concretely, so a one-member marker would be ceremony.
 *
 * @param toolUseId the id of the {@link ToolCallBlock} this answers
 * @param content what the tool produced, never null, never containing null
 * @param isError whether the tool itself reported failure — in-band, so the model reads it and
 *     reacts. Distinct from the engine failing to produce any tool output at all, which never
 *     reaches the wire as a result block.
 */
public record ToolResultBlock(
    CallId toolUseId, List<ToolResultContentBlock> content, boolean isError) implements Block {

  public ToolResultBlock {
    Objects.requireNonNull(toolUseId, "toolUseId must not be null");
    Objects.requireNonNull(content, "content must not be null");
    content = List.copyOf(content);
  }

  /**
   * The one place a {@link ToolResult} becomes wire content: identity is attached here, and the
   * sealed success/failure distinction flattens to the {@code isError} flag the providers model.
   * Keeping it in a single method is what stops the two representations drifting apart.
   */
  public static ToolResultBlock of(CallId toolUseId, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return switch (result) {
      case ToolResult.Success success -> new ToolResultBlock(toolUseId, success.content(), false);
      case ToolResult.Failure failure ->
          new ToolResultBlock(toolUseId, List.of(new TextBlock(failure.message())), true);
    };
  }
}
