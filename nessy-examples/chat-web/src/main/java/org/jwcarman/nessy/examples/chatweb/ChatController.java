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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.narration.odyssey.AgentStreams;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agents")
public class ChatController {

  public record MessageRequest(String text) {}

  public record Decision(String decision, String note) {}

  public record Line(String role, String text) {}

  private final Harness<String> harness;
  private final TurnHistories histories;
  private final AgentStreams streams;
  private final ApprovalStreams approvals;
  private final ApprovalDesk desk;
  private final Replies replies;

  ChatController(
      Harness<String> harness,
      TurnHistories histories,
      AgentStreams streams,
      ApprovalStreams approvals,
      ApprovalDesk desk,
      Replies replies) {
    this.harness = harness;
    this.histories = histories;
    this.streams = streams;
    this.approvals = approvals;
    this.desk = desk;
    this.replies = replies;
  }

  /** What has been said, from the story itself: the page rebuilds from this, not from a replay. */
  @GetMapping("/{id}")
  public Map<String, Object> state(@PathVariable("id") String id) {
    AgentId agentId = agent(id);
    List<Turn> turns = histories.forAgent(ChatConfiguration.TYPE, agentId).turnsFrom(0);
    return Map.of("transcript", lines(turns), "approvals", desk.pending(agentId));
  }

  @PostMapping("/{id}/messages")
  public ResponseEntity<Void> say(@PathVariable("id") String id, @RequestBody MessageRequest body) {
    harness.observe(agent(id), body.text());
    return ResponseEntity.accepted().build();
  }

  /**
   * Ends the conversation. The story is kept -- an ended agent is one that will not take another
   * word, not one that never spoke -- and the page moves on to a fresh id.
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> end(@PathVariable("id") String id) {
    harness.terminate(agent(id));
    return ResponseEntity.accepted().build();
  }

  /**
   * The agent's stream. A browser that reconnects hands back the id of the last event it saw, and
   * gets everything since -- the journal is what makes a page that was closed catch up rather than
   * rebuild.
   */
  @GetMapping("/{id}/events")
  public SseEmitter events(
      @PathVariable("id") String id,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    return streams.resume(ChatConfiguration.TYPE, agent(id), lastEventId);
  }

  /** The desk's questions for this agent, as a stream of their own. */
  @GetMapping("/{id}/approvals/events")
  public SseEmitter approvalEvents(
      @PathVariable("id") String id,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    return approvals.resume(agent(id), lastEventId);
  }

  @PostMapping("/{id}/approvals/{callId}")
  public ResponseEntity<Void> decide(
      @PathVariable("id") String id,
      @PathVariable("callId") String callId,
      @RequestBody Decision body) {
    ApprovalDesk.Waiting question = desk.take(new CallId(callId)).orElse(null);
    if (question == null) {
      // Already answered, by another tab or another person. Not an error: the page should redraw
      // and see what was decided, rather than be shown a stack trace for losing a race.
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    ApprovalResult result =
        "approve".equals(body.decision())
            ? ApprovalResult.approved()
            : ApprovalResult.denied(
                body.note() == null || body.note().isBlank() ? "denied" : body.note());
    return switch (replies.approve(question.replyToken(), result)) {
      case ReplyOutcome.Settled _ -> ResponseEntity.accepted().build();
      // The agent had already moved on -- the term ran out, or it was ended. The card was stale.
      case ReplyOutcome.NotAwaiting _, ReplyOutcome.Unreadable _ ->
          ResponseEntity.status(HttpStatus.CONFLICT).build();
    };
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> malformed(IllegalArgumentException refused) {
    return ResponseEntity.badRequest().body(refused.getMessage());
  }

  /** An id from the address bar; UUID.fromString refuses what is not one, and that is a 400. */
  private static AgentId agent(String id) {
    return new AgentId(UUID.fromString(id));
  }

  private static List<Line> lines(List<Turn> turns) {
    List<Line> lines = new ArrayList<>();
    for (Turn turn : turns) {
      lines.add(new Line("user", text(turn.observation().blocks())));
      for (Exchange exchange : turn.exchanges()) {
        String commentary = text(exchange.request());
        if (!commentary.isBlank()) {
          lines.add(new Line("assistant", commentary));
        }
        exchange.calls().forEach(call -> lines.add(new Line("tool", "🔧 " + call.name().value())));
        exchange
            .outcomes()
            .forEach(
                outcome ->
                    lines.add(
                        new Line(
                            "tool",
                            switch (outcome) {
                              case ToolOutcome.Succeeded(var _, var blocks) -> text(blocks);
                              case ToolOutcome.Failed(var _, String message) ->
                                  "failed: " + message;
                              case ToolOutcome.Denied(var _, String reason) -> "denied: " + reason;
                            })));
      }
      switch (turn.result()) {
        case TurnResult.Answered(var blocks) -> lines.add(new Line("assistant", text(blocks)));
        case TurnResult.Failed _ -> lines.add(new Line("system", "the agent could not answer"));
        case TurnResult.Refused _ -> lines.add(new Line("system", "the agent declined to answer"));
        case null -> {
          // Still being worked on; what it says arrives on the stream.
        }
      }
    }
    return lines;
  }

  /**
   * The prose in a list of blocks: text and commentary, joined; provider payloads are not prose.
   */
  private static String text(List<? extends Block> blocks) {
    return blocks.stream()
        .map(
            block ->
                switch (block) {
                  case Block.Text(String text) -> text;
                  case Block.Commentary(String text) -> text;
                  case Block.Provider _, Block.ToolCall _ -> "";
                })
        .collect(Collectors.joining());
  }
}
