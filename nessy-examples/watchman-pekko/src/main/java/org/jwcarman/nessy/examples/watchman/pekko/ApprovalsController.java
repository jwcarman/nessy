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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The page. Three routes, no API, no JSON: a thing a person looks at every couple of days on a LAN.
 *
 * <p><b>The two buttons are the interesting part, and they are async on purpose.</b> {@link
 * WatchmanActorSystem#answerApproval} returns a {@link CompletableFuture} that the agent completes
 * only AFTER it has persisted the decision, and returning that future from the handler is what
 * makes Spring hold the response open until then.
 *
 * <p>Getting this wrong is easy and quiet: fire a {@code tell} at the actor, return {@code
 * "redirect:/"} immediately, and the operator sees a green page for a decision that was still only
 * a message in a mailbox. Lose power in that instant and the denial is gone while the human
 * believes it landed. The whole difference is {@code ask} instead of {@code tell}, and returning
 * the future instead of a String — and {@code DurableIngestTest} asserts it rather than trusting
 * it.
 *
 * <p>The read side comes from somewhere else entirely: {@link PendingApprovals} reads the agents'
 * own persisted state. So a click here is not "update the row", it is "answer the question", and
 * the page catches up when it is next rendered.
 */
@Controller
public class ApprovalsController {

  private static final Logger LOG = LoggerFactory.getLogger(ApprovalsController.class);

  private final PendingApprovals approvals;
  private final WatchmanActorSystem actors;
  private final Transcript transcript;

  ApprovalsController(
      PendingApprovals approvals, WatchmanActorSystem actors, Transcript transcript) {
    this.approvals = approvals;
    this.actors = actors;
    this.transcript = transcript;
  }

  /** What is waiting, longest wait first. */
  @GetMapping("/")
  public String pending(Model model) {
    List<PendingApprovals.Row> rows = approvals.pending();
    model.addAttribute("rows", rows);
    return "index";
  }

  /**
   * The watchman's notes, read straight from the journal — no actor involved, and no limit on how
   * far back it goes. This page is the reason the transcript being append-only pays twice: it is
   * cheap to read and every entry carries the time it was written.
   */
  @GetMapping("/transcript")
  public String transcript(Model model) {
    model.addAttribute("entries", transcript.entries(WatchmanGuardian.WATCHMAN));
    return "transcript";
  }

  /**
   * Yes. The principal is whoever authenticated, never a form field — an answer this page cannot
   * attribute is an answer it should not take.
   */
  @PostMapping("/approve/{agent}/{callId}")
  public CompletableFuture<String> approve(
      @PathVariable("agent") String agent,
      @PathVariable("callId") String callId,
      @RequestParam(name = "note", defaultValue = "") String note,
      Principal principal) {
    return answer(agent, callId, true, principal.getName(), note);
  }

  /** No, and why — the reason becomes the note the model reads next round. */
  @PostMapping("/deny/{agent}/{callId}")
  public CompletableFuture<String> deny(
      @PathVariable("agent") String agent,
      @PathVariable("callId") String callId,
      @RequestParam(name = "reason", defaultValue = "") String reason,
      Principal principal) {
    return answer(agent, callId, false, principal.getName(), reason);
  }

  /**
   * The handler does not resolve its view until the agent has confirmed the decision is durable. A
   * timeout or a refusal is a 500, not a redirect: the operator must be able to tell the difference
   * between "recorded" and "we are not sure".
   */
  private CompletableFuture<String> answer(
      String agent, String callId, boolean approved, String by, String note) {
    return actors
        .answerApproval(agent, callId, approved, by, note)
        .thenApply(
            ack -> {
              if (!ack.accepted()) {
                throw new IllegalStateException("the watchman refused the answer: " + ack.detail());
              }
              LOG.info("[watchman] {} {} call {} for {}", by, ack.detail(), callId, agent);
              return "redirect:/";
            })
        .toCompletableFuture();
  }
}
