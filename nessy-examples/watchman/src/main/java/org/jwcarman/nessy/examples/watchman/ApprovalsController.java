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
import java.util.concurrent.CompletableFuture;
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
 * The page. Two routes, no API, no JSON: a thing a person looks at every couple of days on a LAN.
 *
 * <p><b>The two buttons are the interesting part, and they are async on purpose.</b> {@link
 * Replies#approve} returns a {@link CompletableFuture} the engine completes only after it has
 * accepted the decision, and returning that future from the handler is what makes Spring hold the
 * response open until then.
 *
 * <p>Getting this wrong is easy and quiet: fire the answer off, return {@code "redirect:/"}
 * immediately, and the operator sees a green page for a decision that was still only a message in a
 * mailbox. Lose power in that instant and the denial is gone while the human believes it landed.
 *
 * <p>The read side comes from somewhere else entirely: the projection is written as the agent
 * narrates. So a click here is not "update the row", it is "answer the question", and the page
 * catches up when it is next rendered.
 */
@Controller
public class ApprovalsController {

  private static final Logger LOG = LoggerFactory.getLogger(ApprovalsController.class);

  private final PendingApprovalsRepository approvals;
  private final Replies replies;

  ApprovalsController(PendingApprovalsRepository approvals, Replies replies) {
    this.approvals = approvals;
    this.replies = replies;
  }

  @GetMapping("/")
  public String pending(Model model) {
    model.addAttribute("pending", approvals.pending());
    return "approvals";
  }

  @PostMapping("/approvals/{callId}/approve")
  public CompletableFuture<String> approve(@PathVariable String callId, Principal who) {
    return answer(callId, ApprovalResult.approved(), who);
  }

  @PostMapping("/approvals/{callId}/deny")
  public CompletableFuture<String> deny(
      @PathVariable String callId, @RequestParam(defaultValue = "") String note, Principal who) {
    return answer(callId, ApprovalResult.denied(note.isBlank() ? "denied" : note), who);
  }

  /**
   * Answers one question, if it is still waiting.
   *
   * <p>A row that is already answered is not an error: two people can have the page open, and the
   * second click should redirect to a page that shows what the first one decided rather than to a
   * stack trace.
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

  private static String name(Principal who) {
    return who == null ? "someone" : who.getName();
  }
}
