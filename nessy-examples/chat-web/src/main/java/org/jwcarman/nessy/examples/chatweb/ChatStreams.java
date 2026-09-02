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
package org.jwcarman.nessy.examples.chatweb;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentSubscription;
import org.jwcarman.nessy.api.Harness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The bridge from what an agent narrates to what a browser sees.
 *
 * <p><b>The stream is not the reply to a request.</b> {@code observe} posts a line and returns; the
 * answer arrives later, on other threads, to whoever is subscribed. So the page opens ONE
 * long-lived stream per agent and posts messages separately, rather than streaming a response to
 * each POST. That is not a stylistic preference — it is the only shape that survives the truth of
 * the engine: a turn started by one browser tab is narrated to all of them, an answer that arrives
 * while nobody is looking is not lost, and a tool that finishes an hour after the request that
 * triggered it still has somewhere to report.
 *
 * <p>One subscription per agent, however many tabs: the subscription is opened when the first
 * listener arrives and closed when the last one leaves, so an agent nobody is watching costs
 * nothing.
 */
@Component
public class ChatStreams {

  private static final Logger LOG = LoggerFactory.getLogger(ChatStreams.class);

  /** Every listener on one agent, and the single subscription feeding them. */
  private record Audience(List<SseEmitter> emitters, AgentSubscription subscription) {}

  private final Map<String, Audience> audiences = new ConcurrentHashMap<>();
  private final Harness<String> harness;
  private final ApprovalDesk desk;

  ChatStreams(Harness<String> harness, ApprovalDesk desk) {
    this.harness = harness;
    this.desk = desk;
  }

  /** Opens one browser's stream onto an agent, subscribing to that agent if nobody else was. */
  public SseEmitter open(AgentId agentId) {
    return open(agentId, null);
  }

  /**
   * Opens a stream, resuming from {@code lastEventId} when this is the first listener.
   *
   * <p>The cursor only takes effect when the subscription is CREATED. One subscription serves every
   * tab watching an agent, so a browser joining an audience that already exists gets the live feed
   * from now — there is no per-listener replay, because the events it missed were already delivered
   * to the ones that were there. What this fixes is the case that actually loses events: the last
   * tab closes, the subscription goes, and a browser comes back to find a gap.
   */
  public SseEmitter open(AgentId agentId, String lastEventId) {
    // No timeout: a chat page is open for as long as someone has the tab open, and a stream that
    // expires mid-thought looks exactly like the agent dying.
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
    Audience audience =
        audiences.compute(
            agentId.value(),
            (id, existing) -> {
              if (existing != null) {
                existing.emitters().add(emitter);
                return existing;
              }
              List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
              emitters.add(emitter);
              return new Audience(
                  emitters, harness.subscribe(agentId, subscriber(agentId), lastEventId));
            });
    emitter.onCompletion(() -> close(agentId, emitter));
    emitter.onTimeout(() -> close(agentId, emitter));
    emitter.onError(failure -> close(agentId, emitter));
    send(null, audience.emitters(), List.of(emitter), "ready", Map.of());
    return emitter;
  }

  private void close(AgentId agentId, SseEmitter emitter) {
    audiences.computeIfPresent(
        agentId.value(),
        (id, audience) -> {
          audience.emitters().remove(emitter);
          if (!audience.emitters().isEmpty()) {
            return audience;
          }
          // Nobody is watching. Stop listening, so an idle agent leaks no routing entry.
          audience.subscription().close();
          return null;
        });
  }

  /**
   * What the browser is told, which is a translation and not a dump: the page needs the deltas, the
   * tool traffic, and the two moments that change what its input box may do.
   */
  private AgentSubscriber subscriber(AgentId agentId) {
    return event -> {
      List<SseEmitter> listeners = listeners(agentId);
      // Stamped on every event so the browser records it and hands it back as Last-Event-ID when
      // its connection drops. Nessy's event ids are UUIDv7 — time-ordered — which is what lets one
      // double as a cursor.
      String cursor = event.id();
      switch (event) {
        case AgentEvent.TurnStarted started -> emit(cursor, listeners, "busy", Map.of());
        case AgentEvent.TextDelta delta ->
            emit(cursor, listeners, "delta", Map.of("text", delta.text()));
        case AgentEvent.ReasoningDelta thinking ->
            emit(cursor, listeners, "thinking", Map.of("text", thinking.text()));
        case AgentEvent.ToolCallRequested call ->
            emit(
                cursor,
                listeners,
                "tool-requested",
                Map.of(
                    "id",
                    call.callId().value(),
                    "name",
                    call.toolName(),
                    "what",
                    call.description()));
        case AgentEvent.ApprovalRequested asked ->
            emit(cursor, listeners, "approval", desk.card(asked.callId()));
        case AgentEvent.ApprovalDecided decided ->
            emit(
                cursor,
                listeners,
                "tool-decided",
                Map.of(
                    "id",
                    decided.callId(),
                    "allowed",
                    decided.result()
                        instanceof org.jwcarman.nessy.api.tool.ApprovalResult.Approved));
        case AgentEvent.ToolCallCompleted done ->
            emit(
                cursor,
                listeners,
                "tool-completed",
                Map.of(
                    "id",
                    done.callId(),
                    "error",
                    done.result() instanceof org.jwcarman.nessy.api.tool.ToolResult.Failure));
        case AgentEvent.TurnEnded ended -> emit(cursor, listeners, "idle", Map.of());
        default -> {
          // Everything else is narration this page has no use for.
        }
      }
    };
  }

  private List<SseEmitter> listeners(AgentId agentId) {
    Audience audience = audiences.get(agentId.value());
    return audience == null ? List.of() : audience.emitters();
  }

  private void emit(
      String cursor, List<SseEmitter> listeners, String name, Map<String, ?> payload) {
    send(cursor, listeners, listeners, name, payload);
  }

  /**
   * Sends to {@code targets}, forgetting any that has gone away.
   *
   * <p>A browser closing a tab mid-turn is ordinary, not exceptional: the write fails, the emitter
   * is dropped, and the turn it was narrating carries on regardless — which is the whole reason
   * this narration is not the response to the request that started the work.
   */
  private void send(
      String cursor,
      List<SseEmitter> all,
      List<SseEmitter> targets,
      String name,
      Map<String, ?> payload) {
    for (SseEmitter emitter : targets) {
      try {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(payload);
        if (cursor != null) {
          // Only a narrated event has an id worth handing back as Last-Event-ID. "ready" is this
          // server talking about itself, and stamping it would give the browser a cursor that
          // names nothing — SseEventBuilder.id(null) throws outright, so the null is load-bearing.
          event = event.id(cursor);
        }
        emitter.send(event);
      } catch (IOException | IllegalStateException gone) {
        LOG.debug("dropping a listener that went away", gone);
        all.remove(emitter);
      }
    }
  }
}
