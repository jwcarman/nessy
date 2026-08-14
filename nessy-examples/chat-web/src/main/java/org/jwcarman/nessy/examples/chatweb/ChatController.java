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

import java.util.List;
import java.util.Map;
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
 * on the starter's {@link TurnRunner}, narrated through {@link TurnEventSse} — including the
 * terminal {@code done} event, which the framework now emits itself — so this controller's own job
 * shrinks to identity (which agent, which conversation) and closing the emitter once the turn
 * returns, shared with {@link ApprovalController}'s resumed-segment stream via {@link #done}.
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
   * <p>{@link TurnRunner#run} passes the emitter it created straight into this closure as its
   * argument — captured before the virtual thread that will invoke {@code turn} ever starts — so
   * the observer built here closes over that emitter directly, with no bridge and no window in
   * which it could be unpopulated.
   */
  static SseEmitter runTurn(TurnRunner turnRunner, Function<TurnObserver, RunOutcome> turn) {
    return turnRunner.run(
        emitter -> turn.apply(TurnEventSse.observer(e -> TurnEventSse.send(emitter, e))),
        ChatController::done);
  }

  /**
   * The shared tail of both entry and resume streams. The terminal {@code done} event no longer
   * lives here — the observer the turn streamed through already narrated it live, from {@code
   * TurnEvent.TurnEnded}, the same beat the framework saved the ending — so this is now only the
   * cleanup a normally-returning turn still needs: closing the emitter. {@code outcome} stays a
   * parameter (the shared {@link TurnRunner#run} shape both this and {@link ApprovalController}
   * call into) even though this method no longer reads it.
   */
  static void done(SseEmitter emitter, RunOutcome outcome) {
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
