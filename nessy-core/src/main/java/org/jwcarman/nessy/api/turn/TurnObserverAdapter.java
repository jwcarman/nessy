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

/**
 * A {@link TurnObserver} that unpacks the narration into one overridable hook per {@link TurnEvent}
 * variant, each a no-op until a subclass says otherwise.
 *
 * <p>A lambda observer suits a consumer with one concern ({@code event -> journal.add(event)});
 * this base class suits the selective narrator — a UI that paints text and tool activity but
 * ignores thinking, say — which overrides only the hooks it watches and inherits silence for the
 * rest:
 *
 * <pre>{@code
 * conversation.tell(input, new TurnObserverAdapter() {
 *   @Override
 *   protected void onTextDelta(TurnEvent.TextDelta event) {
 *     terminal.print(event.text());
 *   }
 * });
 * }</pre>
 *
 * <p>The dispatch switch is core code, so it is exhaustive with no {@code default} arm (sealed
 * etiquette): when the {@code TurnEvent} grammar grows a variant, this class fails to compile until
 * the variant gets a hook — and every subclass then inherits the new hook's no-op for free. That is
 * the adapter's promise: extenders never need a defensive arm of their own.
 */
public abstract class TurnObserverAdapter implements TurnObserver {

  /** Routes each event to its hook. Final on purpose: subclasses override hooks, not dispatch. */
  @Override
  public final void on(TurnEvent event) {
    switch (event) {
      case TurnEvent.TextDelta e -> onTextDelta(e);
      case TurnEvent.ThinkingDelta e -> onThinkingDelta(e);
      case TurnEvent.RedactedThinking e -> onRedactedThinking(e);
      case TurnEvent.ToolCallRequested e -> onToolCallRequested(e);
      case TurnEvent.ToolCallDecided e -> onToolCallDecided(e);
      case TurnEvent.ToolCallCompleted e -> onToolCallCompleted(e);
      case TurnEvent.ToolCallProgressed e -> onToolCallProgressed(e);
      case TurnEvent.ToolCallParked e -> onToolCallParked(e);
      case TurnEvent.AssistantSaid e -> onAssistantSaid(e);
      case TurnEvent.TurnEnded e -> onTurnEnded(e);
    }
  }

  /** A chunk of assistant prose arrived from the stream. */
  protected void onTextDelta(TurnEvent.TextDelta event) {
    // no-op until a subclass cares
  }

  /** A chunk of the model's visible reasoning arrived from the stream. */
  protected void onThinkingDelta(TurnEvent.ThinkingDelta event) {
    // no-op until a subclass cares
  }

  /** A complete redacted-thinking block arrived; its contents are opaque by design. */
  protected void onRedactedThinking(TurnEvent.RedactedThinking event) {
    // no-op until a subclass cares
  }

  /** The model asked for homework — emitted mid-stream as the tool-use block materializes. */
  protected void onToolCallRequested(TurnEvent.ToolCallRequested event) {
    // no-op until a subclass cares
  }

  /** The gate's verdict for one call: approved, or denied with reason. */
  protected void onToolCallDecided(TurnEvent.ToolCallDecided event) {
    // no-op until a subclass cares
  }

  /** One piece of homework settled — result in hand, success or error. */
  protected void onToolCallCompleted(TurnEvent.ToolCallCompleted event) {
    // no-op until a subclass cares
  }

  /** A running tool reported progress — the executor attaches the authoritative call. */
  protected void onToolCallProgressed(TurnEvent.ToolCallProgressed event) {
    // no-op until a subclass cares
  }

  /** The call parked — waiting on something that outlives this process. */
  protected void onToolCallParked(TurnEvent.ToolCallParked event) {
    // no-op until a subclass cares
  }

  /** A settled assistant-role message — the deltas were the preview, this is the sentence. */
  protected void onAssistantSaid(TurnEvent.AssistantSaid event) {
    // no-op until a subclass cares
  }

  /** The segment's closing line — emitted exactly once at every exit. */
  protected void onTurnEnded(TurnEvent.TurnEnded event) {
    // no-op until a subclass cares
  }
}
