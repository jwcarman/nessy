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
package org.jwcarman.nessy.api.turn;

import java.util.Objects;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The live story of one turn, for whoever is watching it happen.
 *
 * <p>Narration, not record: none of these ever fold into conversation state. The roster is chosen
 * so a sitting consumer can tell the turn's story from these events alone — the model speaking and
 * thinking, homework requested, the gate's verdict, homework settled. Delivered to the {@link
 * TurnObserver} bound at entry ({@code tell} or {@code resume}); the observer sees the segment it
 * holds, and anything it missed is in the facts.
 *
 * <p>Sealed-grammar etiquette: core switches over this type are exhaustive with no {@code default}
 * arm; extender code is advised to include one for forward tolerance across majors.
 */
public sealed interface TurnEvent {

  /** A chunk of assistant prose arrived from the stream. */
  record TextDelta(String text) implements TurnEvent {
    public TextDelta {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** A chunk of the model's visible reasoning arrived from the stream. */
  record ThinkingDelta(String text) implements TurnEvent {
    public ThinkingDelta {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** A complete redacted-thinking block arrived; its contents are opaque by design. */
  record RedactedThinking(String data) implements TurnEvent {
    public RedactedThinking {
      Objects.requireNonNull(data, "data must not be null");
    }
  }

  /** The model asked for homework — emitted mid-stream as the tool-use block materializes. */
  record ToolCallRequested(ToolCall call) implements TurnEvent {
    public ToolCallRequested {
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /** The gate's verdict for one call: approved, or denied with reason. */
  record ToolCallDecided(ToolCall call, Decision decision) implements TurnEvent {
    public ToolCallDecided {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(decision, "decision must not be null");
    }
  }

  /** One piece of homework settled — result in hand, success or error. */
  record ToolCallCompleted(ToolCall call, ToolResult result) implements TurnEvent {
    public ToolCallCompleted {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(result, "result must not be null");
    }
  }
}
