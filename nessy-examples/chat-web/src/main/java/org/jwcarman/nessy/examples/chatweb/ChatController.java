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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.autoconfigure.web.TurnEventSse;
import org.jwcarman.nessy.autoconfigure.web.TurnRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The page-rebuild endpoint and the entry-message endpoint (spec §4's table). The turn itself runs
 * on the starter's {@link TurnRunner}, narrated through {@link TurnEventSse}; this controller's own
 * job shrinks to identity (which agent, which conversation) and the terminal {@code done} event,
 * which {@link ApprovalController}'s resumed-segment stream shares via {@link #done}.
 */
@RestController
@RequestMapping("/api/conversations")
public final class ChatController {

  private final Agent<String> agent;
  private final TurnRunner turnRunner;

  public ChatController(Agent<String> agent, TurnRunner turnRunner) {
    this.agent = agent;
    this.turnRunner = turnRunner;
  }

  /**
   * Page-rebuild reading: transcript, pending approval cards, and status — no model call. Total
   * over {@link Agent#snapshot}: a browser-minted fresh id redraws as an empty, idle conversation
   * rather than erroring. This is also the losing side of the park-narration race — a concurrent
   * driver whose SSE stream saw zero {@code tool-parked} events still finds its card here.
   */
  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable String id) {
    ConversationSnapshot snapshot = agent.snapshot(new ConversationId(id));
    List<TranscriptView.Line> transcript = TranscriptView.of(snapshot.context());
    List<Map<String, Object>> approvals =
        snapshot.parkedCalls().stream().map(ChatController::approvalCard).toList();
    return Map.of(
        "status", snapshot.status().name(), "transcript", transcript, "approvals", approvals);
  }

  /** Runs this entry's segment on a virtual thread, streaming its narration as it happens. */
  @PostMapping("/{id}/messages")
  public SseEmitter postMessage(@PathVariable String id, @RequestBody MessageRequest body) {
    ConversationId conversationId = new ConversationId(id);
    return runTurn(
        turnRunner, observer -> agent.conversation(conversationId).tell(body.text(), observer));
  }

  /**
   * Shared turn-driving glue for both the entry stream here and {@link ApprovalController}'s
   * resumed-segment stream: builds a {@link TurnEventSse#observer} that narrates onto {@link
   * TurnRunner}'s own emitter and hands it to {@code turn}.
   *
   * <p>{@link TurnRunner#run} only returns the {@link SseEmitter} once it has already started the
   * virtual thread that will invoke {@code turn}, so the observer built for that thread cannot
   * close over the emitter directly — it closes over this {@link AtomicReference} instead, set
   * immediately below once {@code run} hands the emitter back. The turn's own work (a store lookup,
   * building the model request, the network round trip) is far slower than the handful of
   * calling-thread instructions between {@code run} returning and the reference being set, so no
   * narration event is ever sent before the reference is populated.
   */
  static SseEmitter runTurn(TurnRunner turnRunner, Function<TurnObserver, RunOutcome> turn) {
    AtomicReference<SseEmitter> emitterRef = new AtomicReference<>();
    SseEmitter emitter =
        turnRunner.run(
            () -> turn.apply(TurnEventSse.observer(e -> TurnEventSse.send(emitterRef.get(), e))),
            ChatController::done);
    emitterRef.set(emitter);
    return emitter;
  }

  /**
   * The shared tail of both entry and resume streams: the terminal {@code done} event carrying
   * final status (and {@code failureReason} when the turn failed). Any {@code tool-parked} cards
   * for a newly parked call were already narrated live by the observer the turn itself streamed
   * through — this no longer re-derives them from {@code outcome}, so the live park event is the
   * single card source for the stream (the page-rebuild snapshot in {@link #get} is the separate,
   * still-needed source for a reader who missed that live event, e.g. a losing concurrent driver).
   */
  static void done(SseEmitter emitter, RunOutcome outcome) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", outcome.state().status().name());
    String failureReason = outcome.state().failureReason();
    if (failureReason != null) {
      payload.put("failureReason", failureReason);
    }
    TurnEventSse.send(emitter, new TurnEventSse.Event("done", payload));
    emitter.complete();
  }

  /**
   * {@code {token, tool, args}} — the same shape {@link TurnEventSse#of} emits for a live park,
   * used here to redraw pending cards from a {@link ParkedCall} snapshot. Args are pretty-printed
   * via {@link com.fasterxml.jackson.databind.JsonNode#toPrettyString()}, which needs no
   * application-supplied {@code ObjectMapper}.
   */
  static Map<String, Object> approvalCard(ParkedCall parked) {
    return Map.of(
        "token", parked.token().value(),
        "tool", parked.call().name(),
        "args", parked.call().arguments().toPrettyString());
  }

  public record MessageRequest(String text) {}
}
