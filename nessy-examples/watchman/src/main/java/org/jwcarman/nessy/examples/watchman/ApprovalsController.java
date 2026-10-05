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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ApprovalsController {

  private static final Logger LOG = LoggerFactory.getLogger(ApprovalsController.class);

  /**
   * Who answered, which is all this page can honestly say.
   *
   * <p>There is no login and no user store, so there was never a principal to name -- the method
   * parameter that used to be here resolved to null on every request and this is what it always
   * printed. Put authentication in front of this and the answer becomes a real one; until then,
   * saying "someone" is the truth rather than a fallback.
   */
  private static final String SOMEBODY = "someone";

  /** The page's notice when an answer found nothing waiting. */
  static final String NOT_WAITING = "That approval was no longer waiting.";

  /** One fact an enricher recorded on an approval request, as text. */
  public record Fact(String name, String value) {}

  /** An approval request as the page draws it: strings, because a template renders toString. */
  public record Row(
      String idempotencyKey,
      String agentType,
      String agentId,
      String callId,
      String tool,
      String action,
      List<Fact> facts,
      Instant askedAt,
      Instant deadline,
      String dwell) {}

  public record Note(String role, String text) {}

  private final AgentWork work;
  private final Replies replies;
  private final TurnHistories histories;
  private final Clock clock;

  ApprovalsController(AgentWork work, Replies replies, TurnHistories histories, Clock clock) {
    this.work = work;
    this.replies = replies;
    this.histories = histories;
    this.clock = clock;
  }

  @GetMapping("/")
  public String waiting(Model model) {
    Instant now = clock.instant();
    List<Row> rows =
        work.waitingApprovals(Watchman.TYPE).stream().map(request -> row(request, now)).toList();
    model.addAttribute("rows", rows);
    return "index";
  }

  private static Row row(ApprovalRequest request, Instant now) {
    List<Fact> facts =
        request.facts().properties().stream()
            .map(
                entry ->
                    new Fact(
                        entry.getKey(),
                        entry.getValue().isString()
                            ? entry.getValue().asString()
                            : entry.getValue().toString()))
            .toList();
    return new Row(
        request.idempotencyKey().toString(),
        request.agentType().value(),
        request.agentId().value().toString(),
        request.callId().value(),
        request.toolName().value(),
        request.action(),
        facts,
        request.askedAt(),
        request.deadline(),
        dwell(Duration.between(request.askedAt(), now)));
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
      notes.add(new Note("user", text(turn.input().blocks())));
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

  // The agent type, the agent id and the call's idempotency key address the call: a key is unique
  // across every call, where a call id is unique only within one model reply.
  @PostMapping("/approve/{key}")
  public String approve(
      @PathVariable("key") String key,
      @RequestParam(name = "agentType") String agentType,
      @RequestParam(name = "agentId") String agentId,
      RedirectAttributes redirect) {
    return answer(agentType, agentId, key, ApprovalResult.approved(), redirect);
  }

  @PostMapping("/deny/{key}")
  public String deny(
      @PathVariable("key") String key,
      @RequestParam(name = "agentType") String agentType,
      @RequestParam(name = "agentId") String agentId,
      // "reason", the word the form uses and the word ApprovalResult.Denied uses. It read "note"
      // and the form has always sent "reason", so every denial a person typed was bound to
      // nothing and recorded as the literal "denied" -- the one thing a denial exists to carry,
      // dropped in silence.
      @RequestParam(name = "reason", defaultValue = "") String reason,
      RedirectAttributes redirect) {
    return answer(
        agentType,
        agentId,
        key,
        ApprovalResult.denied(reason.isBlank() ? "denied" : reason),
        redirect);
  }

  private String answer(
      String agentType,
      String agentId,
      String key,
      ApprovalResult result,
      RedirectAttributes redirect) {
    IdempotencyKey idempotencyKey = IdempotencyKey.of(UUID.fromString(key));
    LOG.info("[watchman] {} answered {} with {}", SOMEBODY, idempotencyKey, result);
    // Nessy has the last word on whether an answer landed. A call whose term expired seconds ago
    // has already been denied on this person's behalf, and a second click finds nothing waiting.
    switch (replies.approve(
        new AgentType(agentType), new AgentId(UUID.fromString(agentId)), idempotencyKey, result)) {
      case ReplyOutcome.Applied _ -> {
        // The page reads what is waiting from Nessy on its next load; there is nothing to record.
      }
      case ReplyOutcome.Ignored _ -> {
        LOG.info(
            "[watchman] {} answered {}, which was no longer waiting", SOMEBODY, idempotencyKey);
        redirect.addFlashAttribute("notice", NOT_WAITING);
      }
    }
    return "redirect:/";
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

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> malformed(IllegalArgumentException refused) {
    return ResponseEntity.badRequest().body(refused.getMessage());
  }
}
