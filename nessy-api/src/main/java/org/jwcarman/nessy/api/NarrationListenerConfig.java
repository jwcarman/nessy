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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds an {@link NarrationListener} out of handlers for the kinds of event it cares about.
 *
 * <p>Every {@code onX} may be called more than once; handlers run in the order they were added, and
 * a handler for a group of kinds, such as {@link Narration.TurnEnding}, hears each member. Kinds
 * nobody asked about are ignored. {@link #agentType(AgentType)} narrows the whole listener to one
 * kind of agent, which is what most of them want.
 */
public final class NarrationListenerConfig {

  /** What a handler is told: whose event it is, and the event. */
  @FunctionalInterface
  public interface Handler<E extends Narration> {
    void on(AgentType agentType, AgentId agentId, E event);
  }

  /** A handler filed with the class of the events it takes, so it can be handed one safely. */
  private record Filed<E extends Narration>(Class<E> kind, Handler<E> handler) {
    void on(AgentType agentType, AgentId agentId, Narration event) {
      handler.on(agentType, agentId, kind.cast(event));
    }
  }

  private final List<Filed<?>> handlers = new ArrayList<>();
  private AgentType only;

  NarrationListenerConfig() {}

  /** Only events about agents of this type; everything else passes by unheard. */
  public NarrationListenerConfig agentType(AgentType agentType) {
    this.only = Objects.requireNonNull(agentType, "agentType must not be null");
    return this;
  }

  /** A handler for one kind of event, by its class. The named methods below are the usual way. */
  public <E extends Narration> NarrationListenerConfig on(Class<E> kind, Handler<E> handler) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(handler, "handler must not be null");
    handlers.add(new Filed<>(kind, handler));
    return this;
  }

  public NarrationListenerConfig onTurnStarted(Handler<Narration.TurnStarted> handler) {
    return on(Narration.TurnStarted.class, handler);
  }

  public NarrationListenerConfig onThinking(Handler<Narration.Thinking> handler) {
    return on(Narration.Thinking.class, handler);
  }

  public NarrationListenerConfig onAnswered(Handler<Narration.Answered> handler) {
    return on(Narration.Answered.class, handler);
  }

  public NarrationListenerConfig onTurnEnding(Handler<Narration.TurnEnding> handler) {
    return on(Narration.TurnEnding.class, handler);
  }

  public NarrationListenerConfig onTurnStopped(Handler<Narration.TurnStopped> handler) {
    return on(Narration.TurnStopped.class, handler);
  }

  public NarrationListenerConfig onInferenceRetried(Handler<Narration.InferenceRetried> handler) {
    return on(Narration.InferenceRetried.class, handler);
  }

  public NarrationListenerConfig onTurnFailed(Handler<Narration.TurnFailed> handler) {
    return on(Narration.TurnFailed.class, handler);
  }

  public NarrationListenerConfig onTurnRefused(Handler<Narration.TurnRefused> handler) {
    return on(Narration.TurnRefused.class, handler);
  }

  public NarrationListenerConfig onCommentary(Handler<Narration.Commentary> handler) {
    return on(Narration.Commentary.class, handler);
  }

  public NarrationListenerConfig onActionsRequested(Handler<Narration.ActionsRequested> handler) {
    return on(Narration.ActionsRequested.class, handler);
  }

  public NarrationListenerConfig onCallApproved(Handler<Narration.CallApproved> handler) {
    return on(Narration.CallApproved.class, handler);
  }

  public NarrationListenerConfig onCallDenied(Handler<Narration.CallDenied> handler) {
    return on(Narration.CallDenied.class, handler);
  }

  public NarrationListenerConfig onCallFinished(Handler<Narration.CallFinished> handler) {
    return on(Narration.CallFinished.class, handler);
  }

  public NarrationListenerConfig onCallFailed(Handler<Narration.CallFailed> handler) {
    return on(Narration.CallFailed.class, handler);
  }

  public NarrationListenerConfig onTerminated(Handler<Narration.Terminated> handler) {
    return on(Narration.Terminated.class, handler);
  }

  public NarrationListenerConfig onApprovalSought(Handler<Narration.ApprovalSought> handler) {
    return on(Narration.ApprovalSought.class, handler);
  }

  public NarrationListenerConfig onApprovalDeferred(Handler<Narration.ApprovalDeferred> handler) {
    return on(Narration.ApprovalDeferred.class, handler);
  }

  public NarrationListenerConfig onCallDeferred(Handler<Narration.CallDeferred> handler) {
    return on(Narration.CallDeferred.class, handler);
  }

  public NarrationListenerConfig onThinkingDelta(Handler<Narration.ThinkingDelta> handler) {
    return on(Narration.ThinkingDelta.class, handler);
  }

  public NarrationListenerConfig onContentDelta(Handler<Narration.ContentDelta> handler) {
    return on(Narration.ContentDelta.class, handler);
  }

  NarrationListener build() {
    List<Filed<?>> filed = List.copyOf(handlers);
    AgentType filter = only;
    return (agentType, agentId, event) -> {
      if (filter != null && !filter.equals(agentType)) {
        return;
      }
      for (Filed<?> each : filed) {
        if (each.kind().isInstance(event)) {
          each.on(agentType, agentId, event);
        }
      }
    };
  }
}
