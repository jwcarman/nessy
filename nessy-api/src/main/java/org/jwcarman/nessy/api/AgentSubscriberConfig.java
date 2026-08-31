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
package org.jwcarman.nessy.api;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * What {@link AgentSubscriber#of} hands a customizer: a CONFIG, not a builder — fluent setters, no
 * public {@code build()} — composing a subscriber from per-variant consumers. The
 * composition-friendly rung between a bare lambda (one concern, every event) and extending {@link
 * AgentSubscriberAdapter} (a subclass overriding hooks):
 *
 * <pre>{@code
 * var subscriber = AgentSubscriber.of(s -> s
 *     .onTextDelta(delta -> sse.send("text", delta.text()))
 *     .onApprovalRequested(ask -> sse.send("approve", ask.description()))
 *     .onTurnEnded(end -> sse.send("done", end.outcome())));
 * }</pre>
 *
 * <p>Registering the same variant twice CHAINS ({@link Consumer#andThen}) rather than replaces, so
 * independent concerns — a journal and a renderer, say — can both hear the same events, in
 * registration order. Variants never registered stay silent. Dispatch is inherited from {@link
 * AgentSubscriberAdapter}, so there is exactly one switch over the grammar in this package.
 */
public final class AgentSubscriberConfig {

  private Consumer<AgentEvent.TurnStarted> onTurnStarted = event -> {};
  private Consumer<AgentEvent.TextDelta> onTextDelta = event -> {};
  private Consumer<AgentEvent.ReasoningDelta> onReasoningDelta = event -> {};
  private Consumer<AgentEvent.ToolCallRequested> onToolCallRequested = event -> {};
  private Consumer<AgentEvent.ApprovalRequested> onApprovalRequested = event -> {};
  private Consumer<AgentEvent.ApprovalDecided> onApprovalDecided = event -> {};
  private Consumer<AgentEvent.ToolCallCompleted> onToolCallCompleted = event -> {};
  private Consumer<AgentEvent.Answered> onAnswered = event -> {};
  private Consumer<AgentEvent.TurnEnded> onTurnEnded = event -> {};

  AgentSubscriberConfig() {}

  /** Adds a consumer for TurnStarted; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onTurnStarted(Consumer<AgentEvent.TurnStarted> consumer) {
    onTurnStarted = onTurnStarted.andThen(require(consumer));
    return this;
  }

  /** Adds a consumer for TextDelta; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onTextDelta(Consumer<AgentEvent.TextDelta> consumer) {
    onTextDelta = onTextDelta.andThen(require(consumer));
    return this;
  }

  /** Adds a consumer for ReasoningDelta; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onReasoningDelta(Consumer<AgentEvent.ReasoningDelta> consumer) {
    onReasoningDelta = onReasoningDelta.andThen(require(consumer));
    return this;
  }

  /** Adds a consumer for ToolCallRequested; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onToolCallRequested(
      Consumer<AgentEvent.ToolCallRequested> consumer) {
    onToolCallRequested = onToolCallRequested.andThen(require(consumer));
    return this;
  }

  /** Adds a consumer for ApprovalRequested; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onApprovalRequested(
      Consumer<AgentEvent.ApprovalRequested> consumer) {
    onApprovalRequested = onApprovalRequested.andThen(require(consumer));
    return this;
  }

  /** Adds a consumer for ApprovalDecided; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onApprovalDecided(Consumer<AgentEvent.ApprovalDecided> consumer) {
    onApprovalDecided = onApprovalDecided.andThen(require(consumer));
    return this;
  }

  /** Adds a consumer for ToolCallCompleted; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onToolCallCompleted(
      Consumer<AgentEvent.ToolCallCompleted> consumer) {
    onToolCallCompleted = onToolCallCompleted.andThen(require(consumer));
    return this;
  }

  /** Adds a consumer for Answered; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onAnswered(Consumer<AgentEvent.Answered> consumer) {
    onAnswered = onAnswered.andThen(require(consumer));
    return this;
  }

  /** Adds a consumer for TurnEnded; registering twice chains rather than replaces. */
  public AgentSubscriberConfig onTurnEnded(Consumer<AgentEvent.TurnEnded> consumer) {
    onTurnEnded = onTurnEnded.andThen(require(consumer));
    return this;
  }

  /**
   * Turns this config into the subscriber it describes — the factory's own step, never a public
   * {@code build()}. This config may keep being used and rebuilt afterwards without affecting an
   * already-built subscriber.
   */
  AgentSubscriber build() {
    Consumer<AgentEvent.TurnStarted> turnStarted = onTurnStarted;
    Consumer<AgentEvent.TextDelta> textDelta = onTextDelta;
    Consumer<AgentEvent.ReasoningDelta> thinkingDelta = onReasoningDelta;
    Consumer<AgentEvent.ToolCallRequested> toolCallRequested = onToolCallRequested;
    Consumer<AgentEvent.ApprovalRequested> approvalRequested = onApprovalRequested;
    Consumer<AgentEvent.ApprovalDecided> approvalDecided = onApprovalDecided;
    Consumer<AgentEvent.ToolCallCompleted> toolCallCompleted = onToolCallCompleted;
    Consumer<AgentEvent.Answered> assistantSaid = onAnswered;
    Consumer<AgentEvent.TurnEnded> turnEnded = onTurnEnded;
    return new AgentSubscriberAdapter() {

      @Override
      protected void onTurnStarted(AgentEvent.TurnStarted event) {
        turnStarted.accept(event);
      }

      @Override
      protected void onTextDelta(AgentEvent.TextDelta event) {
        textDelta.accept(event);
      }

      @Override
      protected void onReasoningDelta(AgentEvent.ReasoningDelta event) {
        thinkingDelta.accept(event);
      }

      @Override
      protected void onToolCallRequested(AgentEvent.ToolCallRequested event) {
        toolCallRequested.accept(event);
      }

      @Override
      protected void onApprovalRequested(AgentEvent.ApprovalRequested event) {
        approvalRequested.accept(event);
      }

      @Override
      protected void onApprovalDecided(AgentEvent.ApprovalDecided event) {
        approvalDecided.accept(event);
      }

      @Override
      protected void onToolCallCompleted(AgentEvent.ToolCallCompleted event) {
        toolCallCompleted.accept(event);
      }

      @Override
      protected void onAnswered(AgentEvent.Answered event) {
        assistantSaid.accept(event);
      }

      @Override
      protected void onTurnEnded(AgentEvent.TurnEnded event) {
        turnEnded.accept(event);
      }
    };
  }

  private static <T> Consumer<T> require(Consumer<T> consumer) {
    return Objects.requireNonNull(consumer, "consumer must not be null");
  }
}
