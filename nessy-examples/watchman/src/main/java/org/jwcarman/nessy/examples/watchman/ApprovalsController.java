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
package org.jwcarman.nessy.examples.watchman;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ApprovalsController {

  private static final Logger LOG = LoggerFactory.getLogger(ApprovalsController.class);

  /** A row as the page draws it: strings, because a template renders what toString says. */
  public record Row(
      String agentType,
      String agentId,
      String callId,
      String action,
      Instant askedAt,
      String dwell) {}

  public record Note(String role, String text) {}

  private final PendingApprovalsRepository approvals;
  private final Replies replies;
  private final TurnHistories histories;
  private final Clock clock;

  ApprovalsController(
      PendingApprovalsRepository approvals, Replies replies, TurnHistories histories, Clock clock) {
    this.approvals = approvals;
    this.replies = replies;
    this.histories = histories;
    this.clock = clock;
  }

  @GetMapping("/")
  public String pending(Model model) {
    Instant now = clock.instant();
    List<Row> rows =
        approvals.pending().stream()
            .map(
                row ->
                    new Row(
                        row.agentType().value(),
                        row.agentId().value().toString(),
                        row.callId().value(),
                        row.action(),
                        row.askedAt(),
                        dwell(Duration.between(row.askedAt(), now))))
            .toList();
    model.addAttribute("rows", rows);
    return "index";
  }

  @GetMapping("/transcript")
  public String transcript(Model model) {
    model.addAttribute(
        "notes", notes(histories.forAgent(Watchman.TYPE, Watchman.AGENT).turnsFrom(0)));
    return "transcript";
  }

  static List<Note> notes(List<Turn> turns) {
    List<Note> notes = new ArrayList<>();
    for (Turn turn : turns) {
      notes.add(new Note("user", text(turn.observation().blocks())));
      for (Exchange exchange : turn.exchanges()) {
        String commentary = text(exchange.request());
        if (!commentary.isBlank()) {
          notes.add(new Note("assistant", commentary));
        }
        exchange.calls().forEach(call -> notes.add(new Note("calls", call.name().value())));
        exchange
            .outcomes()
            .forEach(
                outcome ->
                    notes.add(
                        new Note(
                            "result",
                            switch (outcome) {
                              case ToolOutcome.Succeeded(var _, var blocks) -> text(blocks);
                              case ToolOutcome.Failed(var _, String message) ->
                                  "failed: " + message;
                              case ToolOutcome.Denied(var _, String reason) -> "denied: " + reason;
                            })));
      }
      switch (turn.result()) {
        case TurnResult.Answered(var blocks) -> notes.add(new Note("assistant", text(blocks)));
        case TurnResult.Failed _ -> notes.add(new Note("system", "the round failed"));
        case TurnResult.Refused _ -> notes.add(new Note("system", "the model refused"));
        case null -> {
          // A round still under way.
        }
      }
    }
    return notes;
  }

  private static String text(List<? extends Block> blocks) {
    return blocks.stream()
        .map(
            block ->
                switch (block) {
                  case Block.Text(String text) -> text;
                  case Block.Commentary(String text) -> text;
                  case Block.Provider _, Block.ToolCall _ -> "";
                })
        .filter(text -> !text.isEmpty())
        .collect(Collectors.joining("\n"));
  }

  // The type is in the path with the id, because an id names an agent only within its type. A
  // link that carried the id alone could not find the row it came from once two kinds of agent
  // are asking.
  @PostMapping("/approve/{agentType}/{agentId}/{callId}")
  public String approve(
      @PathVariable("agentType") String agentType,
      @PathVariable("agentId") String agentId,
      @PathVariable("callId") String callId,
      Principal who) {
    return answer(
        new AgentType(agentType),
        agent(agentId),
        new CallId(callId),
        ApprovalResult.approved(),
        who);
  }

  @PostMapping("/deny/{agentType}/{agentId}/{callId}")
  public String deny(
      @PathVariable("agentType") String agentType,
      @PathVariable("agentId") String agentId,
      @PathVariable("callId") String callId,
      // "reason", the word the form uses and the word ApprovalResult.Denied uses. It read "note"
      // and the form has always sent "reason", so every denial a person typed was bound to
      // nothing and recorded as the literal "denied" -- the one thing a denial exists to carry,
      // dropped in silence.
      @RequestParam(name = "reason", defaultValue = "") String reason,
      Principal who) {
    return answer(
        new AgentType(agentType),
        agent(agentId),
        new CallId(callId),
        ApprovalResult.denied(reason.isBlank() ? "denied" : reason),
        who);
  }

  private String answer(
      AgentType agentType, AgentId agentId, CallId callId, ApprovalResult result, Principal who) {
    PendingApproval row = approvals.byCallId(agentType, agentId, callId).orElse(null);
    if (row == null || !row.waiting()) {
      LOG.info("[watchman] {} answered {}, which was not waiting", name(who), callId.value());
      return "redirect:/";
    }
    LOG.info("[watchman] {} answered {} with {}", name(who), callId.value(), result);
    switch (replies.approve(new ReplyToken(row.replyToken()), result)) {
      case ReplyOutcome.Settled _ -> recordLocally(agentType, agentId, callId, result);
      // The agent gets the last word on whether an answer landed, and it can refuse: a call whose
      // term expired seconds ago has already been denied on this person's behalf. Recording
      // regardless is how the board came to show decisions that never reached the agent.
      case ReplyOutcome.NotAwaiting _ ->
          LOG.warn(
              "[watchman] {} answered {}, but the agent had already moved on",
              name(who),
              callId.value());
      case ReplyOutcome.Unreadable _ ->
          LOG.warn(
              "[watchman] {} answered {} with a token this application cannot read; was the"
                  + " reply key changed?",
              name(who),
              callId.value());
    }
    return "redirect:/";
  }

  /**
   * Written here as well as by the desk when the engine narrates the decision, because the redirect
   * lands before the narration does, and the person who just clicked must not be shown the question
   * they have already answered. Whichever writer arrives second changes nothing.
   */
  void recordLocally(AgentType agentType, AgentId agentId, CallId callId, ApprovalResult result) {
    approvals.answered(
        agentType,
        agentId,
        callId,
        result instanceof ApprovalResult.Approved ? "approved" : "denied",
        result instanceof ApprovalResult.Denied denied ? denied.reason() : null,
        clock.instant());
  }

  static String dwell(Duration waited) {
    long minutes = Math.max(0, waited.toMinutes());
    if (minutes < 60) {
      return minutes + "m";
    }
    long hours = minutes / 60;
    if (hours < 24) {
      return hours + "h " + (minutes % 60) + "m";
    }
    return (hours / 24) + "d " + (hours % 24) + "h";
  }

  private static String name(Principal who) {
    return who == null ? "someone" : who.getName();
  }

  /** An id from the address bar; UUID.fromString refuses what is not one, and that is a 400. */
  private static AgentId agent(String id) {
    return new AgentId(UUID.fromString(id));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> malformed(IllegalArgumentException refused) {
    return ResponseEntity.badRequest().body(refused.getMessage());
  }
}
