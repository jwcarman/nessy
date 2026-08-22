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

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * The model asking for a tool to run. Always on an assistant message.
 *
 * <p>{@code signature}: an opaque provider-issued continuity token, stored with the block and
 * returned verbatim on replay; absent for providers that issue none. Its sibling {@link
 * ThinkingBlock} uses the opposite convention — {@code signature} there is non-null and empty
 * ({@code ""}) means unsigned, an artifact of how Anthropic streams thinking deltas — the two
 * conventions must never be normalized to each other.
 *
 * <p><b>Equality:</b> the signature participates in record equality, deliberately. The block is
 * constructed once, at stream time, and persisted; at-least-once re-drives replay the SAME stored
 * value, so signature-in-equals cannot break the transcript's no-stutter dedup or the fold's
 * idempotency.
 */
public record ToolUseBlock(@JsonUnwrapped ToolCall call, String signature) implements ContentBlock {

  public ToolUseBlock {
    Objects.requireNonNull(call, "call must not be null");
  }

  /** Convenience for providers that issue no continuity token. */
  public ToolUseBlock(ToolCall call) {
    this(call, null);
  }
}
