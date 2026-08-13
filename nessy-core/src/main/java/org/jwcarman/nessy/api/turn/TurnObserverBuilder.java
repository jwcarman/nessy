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
import java.util.function.Consumer;

/**
 * Builds a {@link TurnObserver} from per-variant consumers — the composition-friendly rung between
 * a bare lambda (one concern, every event) and extending {@link TurnObserverAdapter} (a subclass
 * that overrides hooks):
 *
 * <pre>{@code
 * var observer = TurnObserver.builder()
 *     .onTextDelta(delta -> terminal.print(delta.text()))
 *     .onToolCallCompleted(done -> statusBar.flash(done.call().name()))
 *     .build();
 * }</pre>
 *
 * <p>Registering the same variant twice <em>chains</em> ({@link Consumer#andThen}) rather than
 * replaces, so independent concerns — a journal and a renderer, say — can both hear the same
 * events, in registration order. Variants never registered stay silent. Dispatch is inherited from
 * {@link TurnObserverAdapter}, so there is exactly one switch over the grammar in this package.
 */
public final class TurnObserverBuilder {

  private Consumer<TurnEvent.TextDelta> onTextDelta = event -> {};
  private Consumer<TurnEvent.ThinkingDelta> onThinkingDelta = event -> {};
  private Consumer<TurnEvent.RedactedThinking> onRedactedThinking = event -> {};
  private Consumer<TurnEvent.ToolCallRequested> onToolCallRequested = event -> {};
  private Consumer<TurnEvent.ToolCallDecided> onToolCallDecided = event -> {};
  private Consumer<TurnEvent.ToolCallCompleted> onToolCallCompleted = event -> {};
  private Consumer<TurnEvent.ToolCallProgressed> onToolCallProgressed = event -> {};
  private Consumer<TurnEvent.ToolCallParked> onToolCallParked = event -> {};

  TurnObserverBuilder() {}

  public TurnObserverBuilder onTextDelta(Consumer<TurnEvent.TextDelta> consumer) {
    onTextDelta = onTextDelta.andThen(require(consumer));
    return this;
  }

  public TurnObserverBuilder onThinkingDelta(Consumer<TurnEvent.ThinkingDelta> consumer) {
    onThinkingDelta = onThinkingDelta.andThen(require(consumer));
    return this;
  }

  public TurnObserverBuilder onRedactedThinking(Consumer<TurnEvent.RedactedThinking> consumer) {
    onRedactedThinking = onRedactedThinking.andThen(require(consumer));
    return this;
  }

  public TurnObserverBuilder onToolCallRequested(Consumer<TurnEvent.ToolCallRequested> consumer) {
    onToolCallRequested = onToolCallRequested.andThen(require(consumer));
    return this;
  }

  public TurnObserverBuilder onToolCallDecided(Consumer<TurnEvent.ToolCallDecided> consumer) {
    onToolCallDecided = onToolCallDecided.andThen(require(consumer));
    return this;
  }

  public TurnObserverBuilder onToolCallCompleted(Consumer<TurnEvent.ToolCallCompleted> consumer) {
    onToolCallCompleted = onToolCallCompleted.andThen(require(consumer));
    return this;
  }

  public TurnObserverBuilder onToolCallProgressed(Consumer<TurnEvent.ToolCallProgressed> consumer) {
    onToolCallProgressed = onToolCallProgressed.andThen(require(consumer));
    return this;
  }

  public TurnObserverBuilder onToolCallParked(Consumer<TurnEvent.ToolCallParked> consumer) {
    onToolCallParked = onToolCallParked.andThen(require(consumer));
    return this;
  }

  /** The assembled observer; the builder may keep being used and rebuilt without affecting it. */
  public TurnObserver build() {
    Consumer<TurnEvent.TextDelta> text = onTextDelta;
    Consumer<TurnEvent.ThinkingDelta> thinking = onThinkingDelta;
    Consumer<TurnEvent.RedactedThinking> redacted = onRedactedThinking;
    Consumer<TurnEvent.ToolCallRequested> requested = onToolCallRequested;
    Consumer<TurnEvent.ToolCallDecided> decided = onToolCallDecided;
    Consumer<TurnEvent.ToolCallCompleted> completed = onToolCallCompleted;
    Consumer<TurnEvent.ToolCallProgressed> progressed = onToolCallProgressed;
    Consumer<TurnEvent.ToolCallParked> parked = onToolCallParked;
    return new TurnObserverAdapter() {
      @Override
      protected void onTextDelta(TurnEvent.TextDelta event) {
        text.accept(event);
      }

      @Override
      protected void onThinkingDelta(TurnEvent.ThinkingDelta event) {
        thinking.accept(event);
      }

      @Override
      protected void onRedactedThinking(TurnEvent.RedactedThinking event) {
        redacted.accept(event);
      }

      @Override
      protected void onToolCallRequested(TurnEvent.ToolCallRequested event) {
        requested.accept(event);
      }

      @Override
      protected void onToolCallDecided(TurnEvent.ToolCallDecided event) {
        decided.accept(event);
      }

      @Override
      protected void onToolCallCompleted(TurnEvent.ToolCallCompleted event) {
        completed.accept(event);
      }

      @Override
      protected void onToolCallProgressed(TurnEvent.ToolCallProgressed event) {
        progressed.accept(event);
      }

      @Override
      protected void onToolCallParked(TurnEvent.ToolCallParked event) {
        parked.accept(event);
      }
    };
  }

  private static <E> Consumer<E> require(Consumer<E> consumer) {
    return Objects.requireNonNull(consumer, "consumer must not be null");
  }
}
