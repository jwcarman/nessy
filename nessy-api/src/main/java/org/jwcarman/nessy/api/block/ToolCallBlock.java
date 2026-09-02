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

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.Objects;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * The model asking for a tool to run. Always on an assistant message, and there may be several in
 * one message — that is parallel tool use, and it is why a tool-result message carries a list.
 *
 * <p>Named for the call rather than for Anthropic's {@code tool_use} wire word so that it mirrors
 * its non-wire twin: {@code ToolCall} is what the engine passes around, {@code ToolCallBlock} is
 * what crosses the wire, exactly as {@code ToolResult} pairs with {@link ToolResultBlock}. The wire
 * discriminator stays {@code tool-use} regardless, because stored transcripts name that value.
 *
 * <p><b>Equality:</b> the signature participates in record equality, deliberately. The block is
 * constructed once, at stream time, and persisted; at-least-once re-drives replay the SAME stored
 * value, so signature-in-equals cannot break the transcript's no-stutter dedup or the fold's
 * idempotency.
 */
public record ToolCallBlock(@JsonUnwrapped ToolCall call) implements ExchangeContentBlock {

  public ToolCallBlock {
    Objects.requireNonNull(call, "call must not be null");
  }

  /** The id this call must be answered under. */
  public CallId id() {
    return call.id();
  }
}
