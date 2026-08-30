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

/**
 * An {@link AgentSubscriber} that unpacks the narration into one overridable hook per {@link
 * AgentEvent} variant, each a no-op until a subclass says otherwise.
 *
 * <p>A lambda suits a watcher with one concern ({@code event -> journal.add(event)}); this suits
 * the selective one — a UI that paints text and tool activity but ignores reasoning — which
 * overrides only what it watches and inherits silence for the rest:
 *
 * <pre>{@code
 * harness.subscribe(agentId, new AgentSubscriberAdapter() {
 *   @Override
 *   protected void onTextDelta(AgentEvent.TextDelta event) {
 *     terminal.print(event.text());
 *   }
 * });
 * }</pre>
 *
 * <p>The dispatch switch is core code, so it is exhaustive with no {@code default} arm: when the
 * grammar grows a variant, THIS class fails to compile until the variant gets a hook — and every
 * subclass then inherits the new hook's no-op for free. That is the adapter's promise, and it is
 * why a ten-variant grammar costs a selective watcher nothing: extenders never need a defensive arm
 * of their own.
 */
public abstract class AgentSubscriberAdapter implements AgentSubscriber {

  /** Routes each event to its hook. Final on purpose: subclasses override hooks, not dispatch. */
  @Override
  public final void on(AgentEvent event) {
    switch (event) {
      case AgentEvent.TurnStarted e -> onTurnStarted(e);
      case AgentEvent.TextDelta e -> onTextDelta(e);
      case AgentEvent.ThinkingDelta e -> onThinkingDelta(e);
      case AgentEvent.RedactedThinking e -> onRedactedThinking(e);
      case AgentEvent.ToolCallRequested e -> onToolCallRequested(e);
      case AgentEvent.ApprovalRequested e -> onApprovalRequested(e);
      case AgentEvent.ApprovalDecided e -> onApprovalDecided(e);
      case AgentEvent.ToolCallCompleted e -> onToolCallCompleted(e);
      case AgentEvent.AssistantSaid e -> onAssistantSaid(e);
      case AgentEvent.TurnEnded e -> onTurnEnded(e);
    }
  }

  /** Work has begun on an observation. */
  protected void onTurnStarted(AgentEvent.TurnStarted event) {}

  /** A chunk of assistant prose arrived from the stream. */
  protected void onTextDelta(AgentEvent.TextDelta event) {}

  /** A chunk of the model's visible reasoning arrived from the stream. */
  protected void onThinkingDelta(AgentEvent.ThinkingDelta event) {}

  /** A complete redacted-thinking block arrived. */
  protected void onRedactedThinking(AgentEvent.RedactedThinking event) {}

  /** The model asked for a tool. */
  protected void onToolCallRequested(AgentEvent.ToolCallRequested event) {}

  /** A person is being waited on. */
  protected void onApprovalRequested(AgentEvent.ApprovalRequested event) {}

  /** The approver answered. */
  protected void onApprovalDecided(AgentEvent.ApprovalDecided event) {}

  /** One tool call settled. */
  protected void onToolCallCompleted(AgentEvent.ToolCallCompleted event) {}

  /** A settled assistant message. */
  protected void onAssistantSaid(AgentEvent.AssistantSaid event) {}

  /** The turn ended. */
  protected void onTurnEnded(AgentEvent.TurnEnded event) {}
}
