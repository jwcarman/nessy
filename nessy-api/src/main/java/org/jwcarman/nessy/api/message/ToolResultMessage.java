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
import org.jwcarman.nessy.api.block.ToolResultBlock;

/**
 * The answer to one assistant turn's tool calls — ALL of them, which is why it holds a list.
 *
 * <p>That shape is load-bearing rather than convenient. Anthropic requires every {@code
 * tool_result} for a set of parallel calls to arrive in a SINGLE message; splitting them across
 * messages does not error, it quietly trains the model to stop making parallel calls. Holding the
 * whole set here means the grouping decision was made by the caller that already knew the answer,
 * so no adapter has to look backwards and reconstruct it — and the broken form is not expressible.
 *
 * <p>Providers disagree about where this lives on the wire: Anthropic carries tool results in a
 * user message, OpenAI gives them their own {@code tool} role. Adapters reconcile that; this type
 * states the intent, which is "these results answer the calls immediately above."
 */
public record ToolResultMessage(List<ToolResultBlock> blocks) implements Message {

  public ToolResultMessage {
    Objects.requireNonNull(blocks, "blocks must not be null");
    blocks = List.copyOf(blocks);
  }
}
