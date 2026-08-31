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

import com.fasterxml.jackson.databind.JsonNode;
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

  /**
   * A chunk of reasoning the model is showing as it thinks.
   *
   * <p>Narration and nothing else: it is streamed to whoever is watching and never stored. Every
   * vendor that shows reasoning has some version of this, and none of them require it back — what
   * they require back is {@link ProviderStateEmitted}, which is a different thing that happens to
   * arrive nearby.
   */
  record ReasoningChunk(String text) implements ModelEvent {
    public ReasoningChunk {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /**
   * The provider handed us something to give back.
   *
   * <p>A signature over reasoning it wants to trust on replay, an encrypted blob it will not show
   * us, a continuity token tied to a call — every vendor invents its own, and this models the
   * PATTERN rather than any one of them. The payload is built by the adapter that will have to read
   * it again, and by nobody else.
   *
   * @param provider whose state this is, so a transcript replayed against a different vendor can
   *     skip what was never theirs
   * @param data whatever that provider needs; opaque here
   */
  record ProviderStateEmitted(String provider, JsonNode data) implements ModelEvent {
    public ProviderStateEmitted {
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(data, "data must not be null");
    }
  }

  /** A complete tool call. Emitted once its arguments have finished arriving. */
  record ToolCallEmitted(ToolCall call) implements ModelEvent {
    public ToolCallEmitted {
      Objects.requireNonNull(call, "call must not be null");
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
