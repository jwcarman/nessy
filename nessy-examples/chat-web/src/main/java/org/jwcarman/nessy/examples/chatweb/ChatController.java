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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/agents")
public class ChatController {

  public record MessageRequest(String text) {}

  public record Decision(String decision, String note) {}

  /** One line of the story; {@code turn} says which turn it belongs to. */
  public record Line(long turn, String role, String text) {}

  /**
   * An approval request as the page draws it. The id is the call's idempotency key: it addresses
   * the answer, and it is the same after a restart.
   */
  public record Card(
      String id, String tool, String args, String what, Instant askedAt, Instant deadline) {}

  private static final JsonMapper EVIDENCE = JsonMapper.builder().build();

  private final QueuedHarness<String> harness;
  private final AgentWork work;
  private final Replies replies;
  private final TurnHistories histories;
  private final AgentStreams streams;

  ChatController(
      QueuedHarness<String> harness,
      AgentWork work,
      Replies replies,
      TurnHistories histories,
      AgentStreams streams) {
    this.harness = harness;
    this.work = work;
    this.replies = replies;
    this.histories = histories;
    this.streams = streams;
  }

  /**
   * What has been said, from the story itself, and what is waiting for a person, from Nessy. The
   * page rebuilds from this, not from a replay, and nothing here is remembered between requests.
   */
  @GetMapping("/{id}")
  public Map<String, Object> state(@PathVariable("id") String id) {
    AgentId agentId = agent(id);
    List<Turn> turns = histories.forAgent(ChatConfiguration.TYPE, agentId).turnsFrom(0);
    AgentStatus status = work.status(ChatConfiguration.TYPE, agentId);
    List<Card> cards = status.waitingApprovals().stream().map(ChatController::card).toList();
    // Working means a turn is in progress: moving, or parked waiting for a person.
    boolean working =
        status.activity() == Activity.WORKING || status.activity() == Activity.WAITING;
    return Map.of("transcript", lines(turns), "approvals", cards, "working", working);
  }

  /**
   * Says something. It is accepted at once and the answer comes on the stream and in the
   * transcript.
   *
   * <p>A message told while a turn is in progress waits its turn and runs after it. The one thing
   * that refuses a message is an agent that has been ended: {@code tell} does not report that, it
   * drops the input, so the status is read first and an ended agent is a {@code 409}. An end that
   * lands between the read and the tell is dropped silently, as {@code tell} drops it.
   */
  @PostMapping("/{id}/messages")
  public ResponseEntity<Map<String, String>> say(
      @PathVariable("id") String id, @RequestBody MessageRequest body) {
    AgentId agentId = agent(id);
    if (work.status(ChatConfiguration.TYPE, agentId).activity() == Activity.ENDED) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("ended", "that conversation has ended"));
    }
    harness.tell(agentId, body.text());
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

  /**
   * Answers an approval request. Nessy has the last word on whether the answer landed: one that
   * arrives after the request was answered, after its deadline, or for a key that is not waiting is
   * ignored, and that is a {@code 409}. The page redraws from the state and sees what is true.
   */
  @PostMapping("/{id}/approvals/{key}")
  public ResponseEntity<Void> decide(
      @PathVariable("id") String id, @PathVariable("key") String key, @RequestBody Decision body) {
    AgentId agentId = agent(id);
    // UUID.fromString refuses what is not one, and that is a 400.
    IdempotencyKey idempotencyKey = IdempotencyKey.of(UUID.fromString(key));
    ApprovalResult result =
        "approve".equals(body.decision())
            ? ApprovalResult.approved()
            : ApprovalResult.denied(
                body.note() == null || body.note().isBlank() ? "denied" : body.note());
    return switch (replies.approve(ChatConfiguration.TYPE, agentId, idempotencyKey, result)) {
      case ReplyOutcome.Applied _ -> ResponseEntity.accepted().build();
      case ReplyOutcome.Ignored _ -> ResponseEntity.status(HttpStatus.CONFLICT).build();
    };
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> malformed(IllegalArgumentException refused) {
    return ResponseEntity.badRequest().body(refused.getMessage());
  }

  private static Card card(ApprovalRequest request) {
    return new Card(
        request.idempotencyKey().toString(),
        request.toolName().value(),
        evidenceOf(request.arguments()),
        request.action(),
        request.askedAt(),
        request.deadline());
  }

  /** The arguments, pretty-printed for a person, or as they came if they will not parse. */
  private static String evidenceOf(String arguments) {
    try {
      return EVIDENCE
          .writerWithDefaultPrettyPrinter()
          .writeValueAsString(EVIDENCE.readTree(arguments));
    } catch (JacksonException notJson) {
      return arguments;
    }
  }

  /** An id from the address bar; UUID.fromString refuses what is not one, and that is a 400. */
  private static AgentId agent(String id) {
    return new AgentId(UUID.fromString(id));
  }

  private static List<Line> lines(List<Turn> turns) {
    List<Line> lines = new ArrayList<>();
    for (Turn turn : turns) {
      long t = turn.id().value();
      lines.add(new Line(t, "user", text(turn.input().blocks())));
      for (Exchange exchange : turn.exchanges()) {
        String commentary = text(exchange.request());
        if (!commentary.isBlank()) {
          lines.add(new Line(t, "assistant", commentary));
        }
        exchange
            .calls()
            .forEach(call -> lines.add(new Line(t, "tool", "🔧 " + call.name().value())));
        exchange
            .outcomes()
            .forEach(
                outcome ->
                    lines.add(
                        new Line(
                            t,
                            "tool",
                            switch (outcome) {
                              case ToolOutcome.Succeeded(var _, var blocks) -> text(blocks);
                              case ToolOutcome.Failed(var _, String message) ->
                                  "failed: " + message;
                              case ToolOutcome.Denied(var _, String reason) -> "denied: " + reason;
                            })));
      }
      switch (turn.result()) {
        case TurnResult.Answered(var blocks) -> lines.add(new Line(t, "assistant", text(blocks)));
        case TurnResult.Failed _ -> lines.add(new Line(t, "system", "the agent could not answer"));
        case TurnResult.Refused _ ->
            lines.add(new Line(t, "system", "the agent declined to answer"));
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
