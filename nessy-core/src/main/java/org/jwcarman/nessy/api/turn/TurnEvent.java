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
import org.jwcarman.nessy.api.ParkToken;
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
 * <p>Two contracts this event makes explicit rather than accidental:
 *
 * <ul>
 *   <li><b>Narration is at-least-once.</b> The loop's write discipline retries on stale saves from
 *       fresh loads, and narration is never transactional with the record (texture never alters it)
 *       — so a retried segment can emit {@link ToolCallParked} twice for one token. This is already
 *       true of every {@code TurnEvent}; the parked event just makes duplicates visible (a doubled
 *       approval card, not a doubled token-consumption — resume idempotency is untouched).
 *       Observers that materialize per-event UI dedupe by the event's natural key — for {@link
 *       ToolCallParked}, the token.
 *   <li><b>The entry-scoped-observer invariant.</b> The token may ride {@link ToolCallParked}
 *       <em>because</em> a {@link TurnObserver} is supplied by the caller of {@code tell}/{@code
 *       resume}, who already holds tokens via {@code RunOutcome} — the event grants nothing to
 *       anyone who lacks it. Capability-bearing events like this one are legal only while observers
 *       are entry-scoped; any future agent-wide standing observer must revisit {@link
 *       ToolCallParked} loudly rather than silently becoming a capability broadcast.
 * </ul>
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
   * The call parked — waiting on something that outlives this process. The token rides this event
   * deliberately: see the type-level javadoc's entry-scoped-observer invariant.
   */
  record ToolCallParked(ToolCall call, ParkToken token) implements TurnEvent {
    public ToolCallParked {
      Objects.requireNonNull(call, CALL_MUST_NOT_BE_NULL);
      Objects.requireNonNull(token, "token must not be null");
    }
  }
}
