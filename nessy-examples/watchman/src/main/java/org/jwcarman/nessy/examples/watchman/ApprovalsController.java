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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.engine.Replies;
import org.jwcarman.nessy.spring.boot.PendingApproval;
import org.jwcarman.nessy.spring.boot.PendingApprovalsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The page. A few routes, no API, no JSON: a thing a person looks at every couple of days on a LAN.
 *
 * <p><b>The two buttons are the interesting part, and they are async on purpose.</b> {@link
 * Replies#approve} returns a future the engine completes only once it has accepted the decision,
 * and returning that future from the handler is what makes Spring hold the response open until
 * then.
 *
 * <p>Getting this wrong is easy and quiet: fire the answer off, redirect immediately, and the
 * operator sees a green page for a decision that was still only a message in a mailbox. Lose power
 * in that instant and the denial is gone while the human believes it landed.
 *
 * <p>The read side comes from somewhere else entirely: the projection is written as the agent
 * narrates. A click here is not "update the row", it is "answer the question", and the page catches
 * up when it is next rendered.
 */
@Controller
public class ApprovalsController {

  private static final Logger LOG = LoggerFactory.getLogger(ApprovalsController.class);

  /** One waiting approval, as the page shows it. */
  public record Row(String agentId, String callId, String action, Instant askedAt, String dwell) {}

  private final PendingApprovalsRepository approvals;
  private final Replies replies;
  private final Memory memory;
  private final Clock clock;

  ApprovalsController(
      PendingApprovalsRepository approvals, Replies replies, Memory memory, Clock clock) {
    this.approvals = approvals;
    this.replies = replies;
    this.memory = memory;
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
                        row.agentId(),
                        row.callId(),
                        row.action(),
                        row.askedAt(),
                        dwell(Duration.between(row.askedAt(), now))))
            .toList();
    model.addAttribute("rows", rows);
    return "index";
  }

  @GetMapping("/transcript")
  public String transcript(Model model) {
    model.addAttribute("notes", notes(memory.recall(WatchmanConfiguration.AGENT)));
    return "transcript";
  }

  /**
   * The transcript as this page wants it, which is NOT {@code Context.lines()}.
   *
   * <p>{@code lines()} is the chat log and says so: tool calls and tool results are invisible there
   * on purpose. That is right for a chat UI and wrong for a watchman, where the interesting part of
   * a round is exactly what it decided to run — an assistant turn that only calls a tool has no
   * text at all, so the page showed the observation, then the final answer, with the work between
   * them simply missing.
   *
   * <p>So this reads the messages directly and renders the three kinds a person wants to see: what
   * was said, what was called, and what came back.
   */
  private static List<Note> notes(Context context) {
    List<Note> notes = new ArrayList<>();
    for (ContextMessage message : context.messages()) {
      switch (message) {
        case UserMessage user ->
            text(user.content()).ifPresent(t -> notes.add(new Note("user", t)));
        case AnswerMessage answer ->
            text(answer.content()).ifPresent(t -> notes.add(new Note("assistant", t)));
        case ExchangeMessage exchange -> {
          commentary(exchange.content()).ifPresent(t -> notes.add(new Note("assistant", t)));
          exchange.calls().forEach(call -> notes.add(new Note("calls", call.call().name())));
          exchange.results().forEach(block -> notes.add(new Note("result", resultText(block))));
        }
        case AmbientMessage ignored -> {
          // Background the model was shown; nobody said it, so it is not a note.
        }
      }
    }
    return notes;
  }

  /** One line of the transcript: who or what it came from, and what it said. */
  public record Note(String role, String text) {}

  /** What the model said while working — its own commentary, not an answer. */
  private static Optional<String> commentary(List<? extends Block> blocks) {
    String joined =
        blocks.stream()
            .filter(CommentaryBlock.class::isInstance)
            .map(block -> ((CommentaryBlock) block).text())
            .collect(Collectors.joining());
    return joined.isBlank() ? Optional.empty() : Optional.of(joined);
  }

  private static Optional<String> text(List<? extends Block> blocks) {
    String joined =
        blocks.stream()
            .filter(TextBlock.class::isInstance)
            .map(block -> ((TextBlock) block).text())
            .collect(Collectors.joining());
    return joined.isBlank() ? Optional.empty() : Optional.of(joined);
  }

  private static String resultText(ToolResultBlock block) {
    String body =
        block.content().stream()
            .filter(TextBlock.class::isInstance)
            .map(b -> ((TextBlock) b).text())
            .collect(Collectors.joining("\n"));
    return block.isError() ? "failed: " + body : body;
  }

  @PostMapping("/approve/{agentId}/{callId}")
  public CompletableFuture<String> approve(
      @PathVariable("agentId") String agentId,
      @PathVariable("callId") String callId,
      Principal who) {
    return answer(callId, ApprovalResult.approved(), who);
  }

  @PostMapping("/deny/{agentId}/{callId}")
  public CompletableFuture<String> deny(
      @PathVariable("agentId") String agentId,
      @PathVariable("callId") String callId,
      @RequestParam(name = "note", defaultValue = "") String note,
      Principal who) {
    return answer(callId, ApprovalResult.denied(note.isBlank() ? "denied" : note), who);
  }

  /**
   * Answers one question, if it is still waiting.
   *
   * <p>A row that is already answered is not an error: two people can have the page open, and the
   * second click should land on a page showing what the first one decided rather than a stack
   * trace.
   */
  private CompletableFuture<String> answer(String callId, ApprovalResult result, Principal who) {
    PendingApproval row = approvals.byCallId(callId).orElse(null);
    if (row == null || !row.waiting()) {
      LOG.info("[watchman] {} answered {}, which was not waiting", name(who), callId);
      return CompletableFuture.completedFuture("redirect:/");
    }
    LOG.info("[watchman] {} answered {} with {}", name(who), callId, result);
    return replies
        .approve(new ReplyToken(row.replyToken()), result)
        .toCompletableFuture()
        .thenApply(ack -> "redirect:/");
  }

  /** The coarsest unit that is still true — a page shows "3h 12m", never "192 minutes". */
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
}
