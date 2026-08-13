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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
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

  private final Agent<String> agent;
  private final ConversationStore store;
  private final ObjectMapper mapper;

  public ChatController(Agent<String> agent, ConversationStore store, ObjectMapper mapper) {
    this.agent = agent;
    this.store = store;
    this.mapper = mapper;
  }

  /** Page-rebuild reading: transcript, pending approval cards, and status — no model call. */
  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable String id) {
    ConversationId conversationId = new ConversationId(id);
    Optional<ConversationStore.Loaded> loaded = store.load(conversationId);
    if (loaded.isEmpty()) {
      return Map.of(
          "status",
          ConversationStatus.IDLE.name(),
          "transcript",
          List.of(),
          "approvals",
          List.of());
    }
    ConversationState state = loaded.get().state();
    List<TranscriptView.Line> transcript = TranscriptView.of(agent.contextFor(conversationId));
    List<Map<String, Object>> approvals =
        state.parkedCalls().stream().map(call -> approvalCard(call, mapper)).toList();
    return Map.of(
        "status", state.status().name(), "transcript", transcript, "approvals", approvals);
  }

  /**
   * Runs this entry's segment on a virtual thread, streaming its {@link SseEvents} as it happens.
   */
  @PostMapping("/{id}/messages")
  public SseEmitter postMessage(@PathVariable String id, @RequestBody MessageRequest body) {
    ConversationId conversationId = new ConversationId(id);
    SseEmitter emitter = new SseEmitter(0L);
    Thread.ofVirtual().start(() -> runTurn(conversationId, body.text(), emitter));
    return emitter;
  }

  private void runTurn(ConversationId conversationId, String text, SseEmitter emitter) {
    try {
      RunOutcome outcome =
          agent.resume(conversationId).tell(text, SseEvents.observer(e -> sendEvent(emitter, e)));
      finish(emitter, outcome, mapper);
    } catch (RuntimeException e) {
      fail(emitter, e);
    }
  }

  /** {@code {token, tool, args}} — args pretty-printed via the injected mapper (spec §4). */
  static Map<String, Object> approvalCard(ParkedCall parked, ObjectMapper mapper) {
    return Map.of(
        "token", parked.token().value(),
        "tool", parked.call().name(),
        "args", prettyArgs(parked.call().arguments(), mapper));
  }

  private static String prettyArgs(JsonNode arguments, ObjectMapper mapper) {
    try {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(arguments);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The shared tail of both entry and resume streams: one {@code approval-needed} per newly parked
   * call, then the terminal {@code done} event carrying final status (and {@code failureReason}
   * when the turn failed).
   */
  static void finish(SseEmitter emitter, RunOutcome outcome, ObjectMapper mapper) {
    if (outcome instanceof RunOutcome.Parked parked) {
      for (ParkedCall call : parked.state().parkedCalls()) {
        sendEvent(emitter, new SseEvents.Event("approval-needed", approvalCard(call, mapper)));
      }
    }
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
