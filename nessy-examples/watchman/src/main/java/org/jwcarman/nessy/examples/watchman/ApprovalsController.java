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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.ApprovalDesk;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.spring.boot.PendingApproval;
import org.jwcarman.nessy.spring.boot.PendingApprovalsRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The page (spec §2.2). Four routes, no API, no JSON: this is a thing a person looks at every
 * couple of days on a LAN.
 *
 * <p>Read and write come from different places on purpose, and that division is the spec's §7 audit
 * division showing through. The list is a query against the starter's projection — a table, because
 * "what is waiting?" is a query and nothing in Nessy answers it otherwise. The two buttons go
 * through {@link ApprovalDesk}, which is the only door that may answer an approval; the row on this
 * page changes later, when the fold publishes the fact. So a click here is not "update the row", it
 * is "answer the question", and the page catches up.
 */
@Controller
public class ApprovalsController {

  private static final int RECENT = 50;

  private final PendingApprovalsRepository approvals;
  private final ApprovalDesk desk;

  ApprovalsController(PendingApprovalsRepository approvals, ApprovalDesk desk) {
    this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
    this.desk = Objects.requireNonNull(desk, "desk must not be null");
  }

  /**
   * One waiting approval, as the page needs it.
   *
   * @param id the computation id, which is what the two buttons post back
   * @param agent the scope that asked
   * @param action the exact command line that will run if this is approved
   * @param parkedAt when it started waiting
   * @param dwell how long it has waited, in words — the number the soak is really about
   * @param request the frozen request as JSON, the evidence behind the decision
   * @param answer {@code approved} or {@code denied}, on the recent page
   * @param note the denial's reason, on the recent page
   */
  public record Row(
      String id,
      String agent,
      String action,
      String parkedAt,
      String dwell,
      String request,
      String answer,
      String note) {}

  /** What is waiting, longest wait first. */
  @GetMapping("/")
  public String pending(Model model) {
    model.addAttribute("rows", rows(approvals.pending()));
    return "index";
  }

  /** The last {@value #RECENT} answered, most recently answered first. */
  @GetMapping("/recent")
  public String recent(Model model) {
    model.addAttribute("rows", rows(approvals.recent(RECENT)));
    return "recent";
  }

  /**
   * Yes. The principal is whoever authenticated, never a form field — an answer this page cannot
   * attribute is an answer it should not take.
   */
  @PostMapping("/approve/{id}")
  public String approve(
      @PathVariable("id") String id,
      @RequestParam(name = "note", defaultValue = "") String note,
      Principal principal) {
    desk.approve(ComputationId.of(id), principal.getName(), note);
    return "redirect:/";
  }

  /** No, and why — the reason becomes the denial's note, which the model reads. */
  @PostMapping("/deny/{id}")
  public String deny(
      @PathVariable("id") String id,
      @RequestParam(name = "reason", defaultValue = "") String reason,
      Principal principal) {
    desk.deny(ComputationId.of(id), principal.getName(), reason);
    return "redirect:/";
  }

  private static List<Row> rows(List<PendingApproval> approvals) {
    return approvals.stream().map(ApprovalsController::row).toList();
  }

  private static Row row(PendingApproval approval) {
    Instant parkedAt = approval.parkedAt().orElse(null);
    return new Row(
        approval.computationId(),
        approval.agentId().orElse("unknown"),
        approval.action().orElse("(no action rendered)"),
        parkedAt == null ? "unknown" : parkedAt.toString(),
        parkedAt == null ? "unknown" : dwell(Duration.between(parkedAt, Instant.now())),
        approval.requestJson().orElse("{}"),
        approval.answer().orElse(""),
        approval.note().orElse(""));
  }

  /**
   * How long, in the coarsest unit that is still true. Days matter here and seconds do not: the
   * whole claim the soak is testing is that an approval can wait days.
   */
  static String dwell(Duration waited) {
    if (waited.isNegative()) {
      return "0m";
    }
    if (waited.toDays() > 0) {
      return waited.toDays() + "d " + waited.toHoursPart() + "h";
    }
    if (waited.toHours() > 0) {
      return waited.toHours() + "h " + waited.toMinutesPart() + "m";
    }
    return waited.toMinutes() + "m";
  }
}
