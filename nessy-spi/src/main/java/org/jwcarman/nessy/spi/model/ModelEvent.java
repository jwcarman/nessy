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
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * One thing a model said while it was saying it.
 *
 * <p>What a provider emits as tokens arrive. Assembled into an assistant message by whoever drains
 * the stream — and narrated on the way past, which is the only reason this granularity exists: a
 * chat interface paints words as they come.
 *
 * <p><b>{@code Stopped}, not {@code TurnEnded}.</b> A model call ending is not a turn ending: a
 * turn is one observation processed to completion, and it may span several calls with tools in
 * between. {@link StopReason#TOOL_USE} is the middle of a turn, never the end of one.
 */
public sealed interface ModelEvent {

  /** A chunk of prose. */
  record TextChunk(String text) implements ModelEvent {
    public TextChunk {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** A chunk of visible reasoning. */
  record ThinkingChunk(String text) implements ModelEvent {
    public ThinkingChunk {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /**
   * The signature for the reasoning just streamed. Arrives after its chunks, which is why thinking
   * is assembled rather than emitted block by block.
   */
  record ThinkingSigned(String signature) implements ModelEvent {
    public ThinkingSigned {
      Objects.requireNonNull(signature, "signature must not be null");
    }
  }

  /** Reasoning the provider encrypted rather than showing. Whole, never chunked. */
  record RedactedThinkingEmitted(String data) implements ModelEvent {
    public RedactedThinkingEmitted {
      Objects.requireNonNull(data, "data must not be null");
    }
  }

  /** A complete tool call. Emitted once its arguments have finished arriving. */
  record ToolCallEmitted(ToolCall call, String signature) implements ModelEvent {
    public ToolCallEmitted {
      Objects.requireNonNull(call, "call must not be null");
    }

    public ToolCallEmitted(ToolCall call) {
      this(call, null);
    }
  }

  /** The call is over, and this is why and what it cost. */
  record Stopped(StopReason reason, Usage usage) implements ModelEvent {
    public Stopped {
      Objects.requireNonNull(reason, "reason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /**
   * A safety classifier declined.
   *
   * <p>Its own arm rather than a {@link StopReason}, for the same reason {@code ModelResult}
   * splits: a refusal may carry no content at all, and a consumer reading content without checking
   * first is the trap the split exists to close.
   */
  record Refused(String category, String explanation, Usage usage) implements ModelEvent {
    public Refused {
      Objects.requireNonNull(category, "category must not be null");
      Objects.requireNonNull(explanation, "explanation must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }
}
