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
package org.jwcarman.nessy;

import java.util.function.Consumer;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.event.ApprovalRequested;
import org.jwcarman.nessy.api.event.ToolProgress;

/**
 * The listener-declaring half of a builder, with sugar per event type.
 *
 * <p>{@code listen}/{@code listenAsync} are the primitives — any type, one registration each. The
 * {@code on*}/{@code on*Async} defaults are readability sugar over them, one pair per member of the
 * listenable vocabulary (the four conversation facts plus the harness events), mirroring {@code
 * TurnObserverConfig}'s per-variant hooks so the whole codebase declares interest the same way:
 *
 * <pre>{@code
 * Nessy.harness(h -> h
 *     .provider(provider)
 *     .onModelResponded(fact -> billing.record(fact.usage()))
 *     .onToolFinishedAsync(fact -> audit.log(fact.call().name())));
 * }</pre>
 *
 * <p>The async sugar uses the log-and-continue error default; an async listener that needs its own
 * error handler declares through {@code listenAsync(Class, Consumer, Consumer)} directly.
 *
 * @param <S> the builder's own type, so sugar calls stay fluent
 */
public interface ListenerDeclarations<S> {

  <T> S listen(Class<T> type, Consumer<T> listener);

  <T> S listenAsync(Class<T> type, Consumer<T> listener);

  /** Sugar: the agent was told something — the entry fact. */
  default S onAgentTold(Consumer<ConversationEvent.AgentTold> listener) {
    return listen(ConversationEvent.AgentTold.class, listener);
  }

  /** Async sugar for {@link #onAgentTold}. */
  default S onAgentToldAsync(Consumer<ConversationEvent.AgentTold> listener) {
    return listenAsync(ConversationEvent.AgentTold.class, listener);
  }

  /** Sugar: the model's settled contribution — message, stop reason, and usage. */
  default S onModelResponded(Consumer<ConversationEvent.ModelResponded> listener) {
    return listen(ConversationEvent.ModelResponded.class, listener);
  }

  /** Async sugar for {@link #onModelResponded}. */
  default S onModelRespondedAsync(Consumer<ConversationEvent.ModelResponded> listener) {
    return listenAsync(ConversationEvent.ModelResponded.class, listener);
  }

  /** Sugar: a model call failed in a way re-performing cannot fix. */
  default S onModelCallFailed(Consumer<ConversationEvent.ModelCallFailed> listener) {
    return listen(ConversationEvent.ModelCallFailed.class, listener);
  }

  /** Async sugar for {@link #onModelCallFailed}. */
  default S onModelCallFailedAsync(Consumer<ConversationEvent.ModelCallFailed> listener) {
    return listenAsync(ConversationEvent.ModelCallFailed.class, listener);
  }

  /** Sugar: one piece of homework settled — success, failure, or denial. */
  default S onToolFinished(Consumer<ConversationEvent.ToolFinished> listener) {
    return listen(ConversationEvent.ToolFinished.class, listener);
  }

  /** Async sugar for {@link #onToolFinished}. */
  default S onToolFinishedAsync(Consumer<ConversationEvent.ToolFinished> listener) {
    return listenAsync(ConversationEvent.ToolFinished.class, listener);
  }

  /** Sugar: a running tool reported progress. */
  default S onToolProgress(Consumer<ToolProgress> listener) {
    return listen(ToolProgress.class, listener);
  }

  /** Async sugar for {@link #onToolProgress}. */
  default S onToolProgressAsync(Consumer<ToolProgress> listener) {
    return listenAsync(ToolProgress.class, listener);
  }

  /** Sugar: the gate is about to consult the approver — a human is being waited on. */
  default S onApprovalRequested(Consumer<ApprovalRequested> listener) {
    return listen(ApprovalRequested.class, listener);
  }

  /** Async sugar for {@link #onApprovalRequested}. */
  default S onApprovalRequestedAsync(Consumer<ApprovalRequested> listener) {
    return listenAsync(ApprovalRequested.class, listener);
  }
}
