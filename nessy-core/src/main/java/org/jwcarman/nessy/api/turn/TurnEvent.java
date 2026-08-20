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
import org.jwcarman.nessy.api.message.Message;
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
 *
 * <p>Narration is at-least-once: the shell's write discipline retries on stale saves, and narration
 * is never transactional with the record — a retried apply can narrate the same event twice.
 * Observers that materialize per-event UI dedupe by the event's natural key. Parking is never
 * narrated at all: a parked call is executor bookkeeping, indistinguishable from a slow one
 * (agent-as-scope §4.3).
 */
public sealed interface TurnEvent {

  /** Shared across the {@code call}-carrying variants below. */
  String CALL_MUST_NOT_BE_NULL = "call must not be null";

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
      Objects.requireNonNull(call, CALL_MUST_NOT_BE_NULL);
    }
  }

  /** The gate's verdict for one call: approved, or denied with reason. */
  record ToolCallDecided(ToolCall call, Decision decision) implements TurnEvent {
    public ToolCallDecided {
      Objects.requireNonNull(call, CALL_MUST_NOT_BE_NULL);
      Objects.requireNonNull(decision, "decision must not be null");
    }
  }

  /** One piece of homework settled — result in hand, success or error. */
  record ToolCallCompleted(ToolCall call, ToolResult result) implements TurnEvent {
    public ToolCallCompleted {
      Objects.requireNonNull(call, CALL_MUST_NOT_BE_NULL);
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /**
   * A running tool reported progress — the executor attaches the authoritative call; the tool's
   * self-reported id is not trusted for narration.
   */
  record ToolCallProgressed(ToolCall call, String message) implements TurnEvent {
    public ToolCallProgressed {
      Objects.requireNonNull(call, CALL_MUST_NOT_BE_NULL);
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * A settled assistant-role message — the deltas ({@link TextDelta}, {@link ThinkingDelta}) were
   * the preview; this is the sentence. Emitted once per model response the fold absorbs, including
   * a response that carries only tool-use blocks and no prose (asking for homework is still saying
   * something; observers wanting prose alone filter for {@link
   * org.jwcarman.nessy.api.message.TextBlock} content). Emitted at the same beat the {@code
   * ModelResponded} fact folds — live narration, subject to this type's at-least-once narration
   * rule (see the type-level javadoc, the same rule {@link AssistantSaid} documents): a retried
   * segment may re-say it, and observers materializing per-event UI should Consumers keying UI on
   * this event dedupe by its natural key.
   */
  record AssistantSaid(Message message) implements TurnEvent {
    public AssistantSaid {
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * The turn's closing line, emitted when a transition lands on {@code Idle}. {@code failureReason}
   * is {@code null} for a completed turn and carries the reason when the turn ended because the
   * model call failed. Subject to the at-least-once narration rule above.
   */
  record TurnEnded(String failureReason) implements TurnEvent {

    /** Whether the turn ended in failure rather than an answer. */
    public boolean failed() {
      return failureReason != null;
    }
  }
}
