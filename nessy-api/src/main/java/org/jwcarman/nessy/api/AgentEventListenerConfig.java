package org.jwcarman.nessy.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds an {@link AgentEventListener} out of handlers for the kinds of event it cares about.
 *
 * <p>Every {@code onX} may be called more than once; handlers for one kind run in the order they
 * were added. Kinds nobody asked about are ignored. {@link #agentType(AgentType)} narrows the whole
 * listener to one kind of agent, which is what most of them want.
 */
public final class AgentEventListenerConfig {

  /** What a handler is told: whose event it is, and the event. */
  @FunctionalInterface
  public interface Handler<E extends AgentEvent> {
    void on(AgentType agentType, AgentId agentId, E event);
  }

  /** A handler filed with the class of the events it takes, so it can be handed one safely. */
  private record Filed<E extends AgentEvent>(Class<E> kind, Handler<E> handler) {
    void on(AgentType agentType, AgentId agentId, AgentEvent event) {
      handler.on(agentType, agentId, kind.cast(event));
    }
  }

  private final Map<Class<? extends AgentEvent>, List<Filed<?>>> handlers = new LinkedHashMap<>();
  private AgentType only;

  AgentEventListenerConfig() {}

  /** Only events about agents of this type; everything else passes by unheard. */
  public AgentEventListenerConfig agentType(AgentType agentType) {
    this.only = Objects.requireNonNull(agentType, "agentType must not be null");
    return this;
  }

  /** A handler for one kind of event, by its class. The named methods below are the usual way. */
  public <E extends AgentEvent> AgentEventListenerConfig on(Class<E> kind, Handler<E> handler) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(handler, "handler must not be null");
    handlers.computeIfAbsent(kind, _ -> new ArrayList<>()).add(new Filed<>(kind, handler));
    return this;
  }

  public AgentEventListenerConfig onTurnStarted(Handler<AgentEvent.TurnStarted> handler) {
    return on(AgentEvent.TurnStarted.class, handler);
  }

  public AgentEventListenerConfig onThinking(Handler<AgentEvent.Thinking> handler) {
    return on(AgentEvent.Thinking.class, handler);
  }

  public AgentEventListenerConfig onAnswered(Handler<AgentEvent.Answered> handler) {
    return on(AgentEvent.Answered.class, handler);
  }

  public AgentEventListenerConfig onTurnEnded(Handler<AgentEvent.TurnEnded> handler) {
    return on(AgentEvent.TurnEnded.class, handler);
  }

  public AgentEventListenerConfig onTurnFailed(Handler<AgentEvent.TurnFailed> handler) {
    return on(AgentEvent.TurnFailed.class, handler);
  }

  public AgentEventListenerConfig onTurnRefused(Handler<AgentEvent.TurnRefused> handler) {
    return on(AgentEvent.TurnRefused.class, handler);
  }

  public AgentEventListenerConfig onCommentary(Handler<AgentEvent.Commentary> handler) {
    return on(AgentEvent.Commentary.class, handler);
  }

  public AgentEventListenerConfig onActionsRequested(Handler<AgentEvent.ActionsRequested> handler) {
    return on(AgentEvent.ActionsRequested.class, handler);
  }

  public AgentEventListenerConfig onCallApproved(Handler<AgentEvent.CallApproved> handler) {
    return on(AgentEvent.CallApproved.class, handler);
  }

  public AgentEventListenerConfig onCallDenied(Handler<AgentEvent.CallDenied> handler) {
    return on(AgentEvent.CallDenied.class, handler);
  }

  public AgentEventListenerConfig onCallFinished(Handler<AgentEvent.CallFinished> handler) {
    return on(AgentEvent.CallFinished.class, handler);
  }

  public AgentEventListenerConfig onCallFailed(Handler<AgentEvent.CallFailed> handler) {
    return on(AgentEvent.CallFailed.class, handler);
  }

  public AgentEventListenerConfig onTerminated(Handler<AgentEvent.Terminated> handler) {
    return on(AgentEvent.Terminated.class, handler);
  }

  public AgentEventListenerConfig onApprovalSought(Handler<AgentEvent.ApprovalSought> handler) {
    return on(AgentEvent.ApprovalSought.class, handler);
  }

  public AgentEventListenerConfig onApprovalDeferred(Handler<AgentEvent.ApprovalDeferred> handler) {
    return on(AgentEvent.ApprovalDeferred.class, handler);
  }

  public AgentEventListenerConfig onCallDeferred(Handler<AgentEvent.CallDeferred> handler) {
    return on(AgentEvent.CallDeferred.class, handler);
  }

  public AgentEventListenerConfig onThinkingDelta(Handler<AgentEvent.ThinkingDelta> handler) {
    return on(AgentEvent.ThinkingDelta.class, handler);
  }

  public AgentEventListenerConfig onContentDelta(Handler<AgentEvent.ContentDelta> handler) {
    return on(AgentEvent.ContentDelta.class, handler);
  }

  AgentEventListener build() {
    Map<Class<? extends AgentEvent>, List<Filed<?>>> byKind = new LinkedHashMap<>();
    handlers.forEach((kind, filed) -> byKind.put(kind, List.copyOf(filed)));
    AgentType filter = only;
    return (agentType, agentId, event) -> {
      if (filter != null && !filter.equals(agentType)) {
        return;
      }
      for (Filed<?> filed : byKind.getOrDefault(event.getClass(), List.of())) {
        filed.on(agentType, agentId, event);
      }
    };
  }
}
