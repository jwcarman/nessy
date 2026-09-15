package org.jwcarman.nessy.examples.chatweb;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Narration, fanned out to every page watching an agent.
 *
 * <p>This is the application's {@link Narrator}: the starter hands it to the engine, and every
 * event about every agent arrives here, as it happens and at most once. Narration is best-effort
 * and not durable -- a page that was closed missed what was said while it was away, and rebuilds
 * itself from the story on load rather than from a replay. That is why there is no cursor here.
 */
@Component
public class ChatStreams implements Narrator {

  private static final Logger LOG = LoggerFactory.getLogger(ChatStreams.class);

  private final Map<AgentId, List<SseEmitter>> audiences = new ConcurrentHashMap<>();
  // Agents whose current inference call has streamed: what they then say whole has been shown
  // already, delta by delta, and showing it again would draw the answer twice. A provider that
  // does not stream never sets this, and its whole answer is drawn once, when it arrives.
  private final Set<AgentId> streamed = ConcurrentHashMap.newKeySet();

  public SseEmitter open(AgentId agentId) {
    // No timeout: a chat page is open for as long as someone has the tab open, and a stream that
    // expires mid-thought looks exactly like the agent dying.
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
    audiences.computeIfAbsent(agentId, id -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> close(agentId, emitter));
    emitter.onTimeout(() -> close(agentId, emitter));
    emitter.onError(failure -> close(agentId, emitter));
    send(List.of(emitter), "ready", Map.of());
    return emitter;
  }

  /** A question for a person, told by the desk once it holds the whole card. */
  public void approval(AgentId agentId, Map<String, ?> card) {
    emit(agentId, "approval", card);
  }

  private void close(AgentId agentId, SseEmitter emitter) {
    audiences.computeIfPresent(
        agentId,
        (id, emitters) -> {
          emitters.remove(emitter);
          // Nobody is watching. Drop the entry, so an idle agent leaks no routing entry.
          return emitters.isEmpty() ? null : emitters;
        });
  }

  @Override
  public void narrate(AgentType agentType, AgentId agentId, AgentEvent event) {
    switch (event) {
      case AgentEvent.TurnStarted _ -> {
        streamed.remove(agentId);
        emit(agentId, "busy", Map.of());
      }
      case AgentEvent.ContentDelta(String text) -> {
        streamed.add(agentId);
        emit(agentId, "delta", Map.of("text", text));
      }
      case AgentEvent.ThinkingDelta(String text) -> emit(agentId, "thinking", Map.of("text", text));
      case AgentEvent.Commentary(String text) -> said(agentId, text);
      case AgentEvent.ActionsRequested(var toolNames) -> {
        toolNames.forEach(name -> emit(agentId, "tool-requested", Map.of("name", name.value())));
        // The next inference call starts fresh: whether it streams is its own affair.
        streamed.remove(agentId);
      }
      case AgentEvent.CallApproved(var callId) ->
          emit(agentId, "tool-decided", Map.of("id", callId.value(), "allowed", true));
      case AgentEvent.CallDenied(var callId, String reason) ->
          emit(
              agentId,
              "tool-decided",
              Map.of("id", callId.value(), "allowed", false, "reason", reason));
      case AgentEvent.CallFinished(var callId) ->
          emit(agentId, "tool-completed", Map.of("id", callId.value(), "error", false));
      case AgentEvent.CallFailed(var callId, String message) ->
          emit(
              agentId,
              "tool-completed",
              Map.of("id", callId.value(), "error", true, "message", message));
      case AgentEvent.Answered(String text) -> {
        said(agentId, text);
        idle(agentId);
      }
      case AgentEvent.TurnFailed _, AgentEvent.TurnRefused _, AgentEvent.Terminated _ ->
          idle(agentId);
      case AgentEvent.Thinking _,
          AgentEvent.ApprovalSought _,
          AgentEvent.ApprovalDeferred _,
          AgentEvent.CallDeferred _ -> {
        // The desk tells the page about approvals itself (see ChatConfiguration.desk); the rest
        // is narration this page has no use for.
      }
    }
  }

  private void said(AgentId agentId, String text) {
    if (!streamed.contains(agentId) && !text.isBlank()) {
      emit(agentId, "delta", Map.of("text", text));
    }
  }

  private void idle(AgentId agentId) {
    streamed.remove(agentId);
    emit(agentId, "idle", Map.of());
  }

  private void emit(AgentId agentId, String name, Map<String, ?> payload) {
    List<SseEmitter> listeners = audiences.get(agentId);
    if (listeners != null) {
      send(listeners, name, payload);
    }
  }

  private static void send(List<SseEmitter> targets, String name, Map<String, ?> payload) {
    for (SseEmitter emitter : targets) {
      try {
        emitter.send(SseEmitter.event().name(name).data(payload));
      } catch (IOException | IllegalStateException gone) {
        LOG.debug("dropping a listener that went away", gone);
        // The emitter's own completion callback removes it from the audience.
        emitter.completeWithError(gone);
      }
    }
  }
}
