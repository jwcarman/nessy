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
package org.jwcarman.nessy.spi.model;

import java.util.Objects;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Something a provider emitted while streaming one turn.
 *
 * <p>Distinct from {@code Event} on purpose: a provider should be able to report what the model
 * did, and nothing else. Reusing the core event type would let a provider inject a user message or
 * an approval decision into the loop.
 */
public sealed interface ModelEvent {

  record TextChunk(String text) implements ModelEvent {}

  /** A chunk of the model's visible reasoning arrived from the stream. */
  record ThinkingChunk(String text) implements ModelEvent {}

  /** The provider finished a thinking block and delivered its signature. */
  record ThinkingSigned(String signature) implements ModelEvent {

    public ThinkingSigned {
      Objects.requireNonNull(signature, "signature must not be null");
    }
  }

  /** A complete redacted-thinking block arrived; its contents are opaque by design. */
  record RedactedThinkingEmitted(String data) implements ModelEvent {

    public RedactedThinkingEmitted {
      Objects.requireNonNull(data, "data must not be null");
    }
  }

  /** Emitted once the provider has assembled a complete tool call. */
  record ToolUseEmitted(ToolCall call) implements ModelEvent {}

  record TurnEnded(StopReason reason, Usage usage) implements ModelEvent {

    public TurnEnded {
      Objects.requireNonNull(reason, "reason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }
}
