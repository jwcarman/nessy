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
import java.util.concurrent.CompletableFuture;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.engine.Replies;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Four routes, and each is one sentence: what has been said, tell the agent something, watch it
 * work, answer its question.
 *
 * <p><b>Posting a message returns nothing.</b> {@code observe} is durable and asynchronous — by the
 * time it returns, the line is the agent's problem and not this request's — so the honest status is
 * 202 with an empty body, and everything the agent then says arrives on {@link #events}. A handler
 * that held the response open until the answer was ready would be inventing a synchrony the engine
 * does not have.
 */
@RestController
@RequestMapping("/api/agents")
public class ChatController {

  public record MessageRequest(String text) {}

  public record Decision(String decision, String note) {}

  private final Harness<String> harness;
  private final Memory memory;
  private final ChatStreams streams;
  private final ApprovalDesk desk;
  private final Replies replies;

  ChatController(
      Harness<String> harness,
      Memory memory,
      ChatStreams streams,
      ApprovalDesk desk,
      Replies replies) {
    this.harness = harness;
    this.memory = memory;
    this.streams = streams;
    this.desk = desk;
    this.replies = replies;
  }

  /**
   * Everything needed to draw the page from cold: the transcript and any questions still waiting.
   *
   * <p>An id the browser has just minted is not an error — an agent that has never been addressed
   * recalls an empty transcript, so a fresh chat draws as an empty one rather than a 404.
   */
  @GetMapping("/{id}")
  public Map<String, Object> state(@PathVariable("id") String id) {
    Context context = memory.recall(AgentId.of(id));
    List<Context.Line> lines = context.lines();
    return Map.of("transcript", lines, "approvals", desk.pending(id));
  }

  /** Says one thing to the agent. The answer comes back on the stream, not here. */
  @PostMapping("/{id}/messages")
  public ResponseEntity<Void> say(@PathVariable("id") String id, @RequestBody MessageRequest body) {
    harness.observe(AgentId.of(id), body.text());
    return ResponseEntity.accepted().build();
  }

  /**
   * The long-lived narration stream for one agent.
   *
   * <p>{@code Last-Event-ID} is sent by the browser on its own, without a line of JavaScript: an
   * EventSource that loses its connection reconnects and reports the id of the last event it
   * actually received. Passing it straight through is what turns a reconnect from a gap into a
   * catch-up — every event carries its id, and Nessy's ids are UUIDv7, so one doubles as a cursor.
   */
  @GetMapping("/{id}/events")
  public SseEmitter events(
      @PathVariable("id") String id,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    return streams.open(AgentId.of(id), lastEventId);
  }

  /**
   * Answers one waiting question.
   *
   * <p>The future is returned rather than awaited: Spring holds the response open until the engine
   * has actually accepted the decision, so a page that says "denied" is a page whose denial landed.
   * Answering and then redirecting optimistically is the quiet way to show a person a decision that
   * was still only a message in a mailbox.
   */
  @PostMapping("/{id}/approvals/{callId}")
  public CompletableFuture<ResponseEntity<Void>> decide(
      @PathVariable("id") String id,
      @PathVariable("callId") String callId,
      @RequestBody Decision body) {
    ApprovalDesk.Waiting question = desk.take(callId).orElse(null);
    if (question == null) {
      // Already answered, by another tab or another person. Not an error: the page should redraw
      // and see what was decided, rather than be shown a stack trace for losing a race.
      return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.CONFLICT).build());
    }
    ApprovalResult result =
        "approve".equals(body.decision())
            ? ApprovalResult.approved()
            : ApprovalResult.denied(
                body.note() == null || body.note().isBlank() ? "denied" : body.note());
    return replies
        .approve(question.replyToken(), result)
        .toCompletableFuture()
        .thenApply(ack -> ResponseEntity.accepted().build());
  }
}
