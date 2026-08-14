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

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The page-rebuild endpoint and the entry-message endpoint (spec §4's table). The turn-narrating
 * bridge from {@link SseEvents} to a live {@link SseEmitter} lives here as package-private statics
 * so {@link ApprovalController}'s resumed-segment stream can share the exact same wire shapes.
 */
@RestController
@RequestMapping("/api/conversations")
public final class ChatController {

  private static final Logger LOGGER = LoggerFactory.getLogger(ChatController.class);

  /**
   * Captures every registered {@code ThreadLocal} accessor (Micrometer's current-{@code
   * Observation} scope among them) so a turn's virtual thread can restore the HTTP request thread's
   * tracing context — see {@link #postMessage}. Package-private: {@link ApprovalController}'s
   * resumed-segment stream shares the exact same wiring.
   */
  static final ContextSnapshotFactory CONTEXT_SNAPSHOT_FACTORY =
      ContextSnapshotFactory.builder().build();

  private final Agent<String> agent;

  public ChatController(Agent<String> agent) {
    this.agent = agent;
  }

  /**
   * Page-rebuild reading: transcript, pending approval cards, and status — no model call. Total
   * over {@link Agent#snapshot}: a browser-minted fresh id redraws as an empty, idle conversation
   * rather than erroring. This is also the losing side of the park-narration race — a concurrent
   * driver whose SSE stream saw zero {@code approval-needed} events still finds its card here.
   */
  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable String id) {
    ConversationSnapshot snapshot = agent.snapshot(new ConversationId(id));
    List<TranscriptView.Line> transcript = TranscriptView.of(snapshot.context());
    List<Map<String, Object>> approvals =
        snapshot.parkedCalls().stream().map(SseEvents::approvalCard).toList();
    return Map.of(
        "status", snapshot.status().name(), "transcript", transcript, "approvals", approvals);
  }

  /**
   * Runs this entry's segment on a virtual thread, streaming its {@link SseEvents} as it happens.
   */
  @PostMapping("/{id}/messages")
  public SseEmitter postMessage(@PathVariable String id, @RequestBody MessageRequest body) {
    ConversationId conversationId = new ConversationId(id);
    SseEmitter emitter = new SseEmitter(0L);
    // A fresh virtual thread starts with an empty current-Observation ThreadLocal, so without
    // this snapshot EngineObservations would parent nessy.run onto nothing and start a NEW trace
    // instead of continuing this HTTP POST's. Capture it here, on the request thread, and restore
    // it inside the virtual thread's runnable.
    ContextSnapshot snapshot = CONTEXT_SNAPSHOT_FACTORY.captureAll();
    Thread.ofVirtual().start(snapshot.wrap(() -> runTurn(conversationId, body.text(), emitter)));
    return emitter;
  }

  private void runTurn(ConversationId conversationId, String text, SseEmitter emitter) {
    try {
      RunOutcome outcome =
          agent
              .conversation(conversationId)
              .tell(text, SseEvents.observer(e -> sendEvent(emitter, e)));
      finish(emitter, outcome);
    } catch (RuntimeException e) {
      fail(emitter, e);
    }
  }

  /**
   * The shared tail of both entry and resume streams: the terminal {@code done} event carrying
   * final status (and {@code failureReason} when the turn failed). Any {@code approval-needed}
   * cards for a newly parked call were already narrated live by the observer {@link
   * SseEvents#observer} wraps into {@link #sendEvent} above — this no longer re-derives them from
   * {@code outcome}, so the live park event is the single card source for the stream (the
   * page-rebuild snapshot in {@link #get} is the separate, still-needed source for a reader who
   * missed that live event, e.g. a losing concurrent driver).
   */
  static void finish(SseEmitter emitter, RunOutcome outcome) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", outcome.state().status().name());
    String failureReason = outcome.state().failureReason();
    if (failureReason != null) {
      payload.put("failureReason", failureReason);
    }
    sendEvent(emitter, new SseEvents.Event("done", payload));
    emitter.complete();
  }

  /** The exceptional tail: a {@code done} naming the error, then the emitter fails loud. */
  static void fail(SseEmitter emitter, RuntimeException e) {
    LOGGER.warn("turn failed", e);
    String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    sendEvent(
        emitter, new SseEvents.Event("done", Map.of("status", "ERROR", "failureReason", reason)));
    emitter.completeWithError(e);
  }

  /**
   * Wraps {@code emitter.send} with the checked-IO try/catch: a closed tab is a broken pipe, not a
   * reason to fail the turn already driving in the background — narration never alters the record.
   */
  static void sendEvent(SseEmitter emitter, SseEvents.Event event) {
    try {
      emitter.send(SseEmitter.event().name(event.name()).data(event.payload()));
    } catch (IOException e) {
      LOGGER.info("SSE send failed, likely a closed client; completing the stream", e);
      emitter.complete();
    }
  }

  public record MessageRequest(String text) {}
}
