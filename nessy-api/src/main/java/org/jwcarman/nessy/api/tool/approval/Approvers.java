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
package org.jwcarman.nessy.api.tool.approval;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.ComputationId;

/** The built-in approvers and the two compositions people reach for (spec §1.4). */
public final class Approvers {

  /**
   * What {@link #defer()} asks for when the approver has no opinion: everything, which the
   * harness's own approval ceiling then clips to what it allows (spec §5). The desk-driven approver
   * genuinely does not know how long its question should stand — nobody is told about it, so nobody
   * is promised anything — and asking for the maximum is the honest way to say so. Never reaches
   * Continuum unclipped: the harness takes {@code min(term, ceiling)} before it creates anything.
   */
  private static final Duration UNTIL_THE_CEILING = ChronoUnit.FOREVER.getDuration();

  private Approvers() {}

  /** Every call runs; no request is built (the executor's rung-0 fast path). */
  public static Approver allow() {
    return Allow.INSTANCE;
  }

  /** Every call is refused with {@code reason}; no request is built. */
  public static Approver deny(String reason) {
    return new Deny(Approval.denied(reason));
  }

  /**
   * Every call is parked for someone else to answer; nobody is told, which is exactly what its
   * callback does. Asks for {@link #UNTIL_THE_CEILING} — see there for why an approver with no
   * opinion asks for the maximum.
   */
  public static Approver defer() {
    return defer(UNTIL_THE_CEILING);
  }

  /**
   * As {@link #defer()}, but for an approver that knows how long its question should stand.
   *
   * @param term how long the question stays answerable, clipped by the harness's approval ceiling
   */
  public static Approver defer(Duration term) {
    Objects.requireNonNull(term, "term must not be null");
    return context -> ApprovalOutcome.deferred(Approvers::tellNobody, term);
  }

  /** {@link #defer()}'s callback: the desk is the notifier, so there is nobody to tell. */
  private static void tellNobody(ComputationId id, Instant deadline) {
    // nothing: whoever polls the pending-approvals projection finds the question on their own
  }

  /**
   * A ladder: rules in order, first answer wins, a {@link Rule.Verdict.Defer} parks, and a ladder
   * that runs out of rules undecided denies loudly rather than approving by omission.
   */
  public static Approver rules(Rule... rules) {
    Objects.requireNonNull(rules, "rules must not be null");
    if (rules.length == 0) {
      throw new IllegalArgumentException("rules must not be empty");
    }
    List<Rule> ordered = List.of(rules);
    return context -> {
      ApprovalRequest request = context.request();
      for (Rule rule : ordered) {
        Rule.Verdict verdict;
        try {
          verdict = rule.judge(request);
        } catch (RuntimeException e) {
          String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          return new ApprovalOutcome.Answered(
              Approval.denied(
                  "rule "
                      + rule.displayName().orElse("#" + ordered.indexOf(rule))
                      + " failed: "
                      + detail));
        }
        switch (verdict) {
          case Rule.Verdict.Answered(Approval approval) -> {
            return new ApprovalOutcome.Answered(approval);
          }
          case Rule.Verdict.Defer _ -> {
            return ApprovalOutcome.deferred(Approvers::tellNobody, UNTIL_THE_CEILING);
          }
          case Rule.Verdict.Undecided _ -> {
            // next rule
          }
        }
      }
      return new ApprovalOutcome.Answered(
          Approval.denied(
              "no rule decided; end a ladder with Rules.allow(), Rules.deny(...) or Rules.defer()"));
    };
  }

  /**
   * Gates: every member must approve; the first denial wins and later members are not consulted.
   * Members answer — a member that defers is a programming error, refused before it can park.
   */
  public static Approver allOf(Approver... approvers) {
    Objects.requireNonNull(approvers, "approvers must not be null");
    if (approvers.length == 0) {
      throw new IllegalArgumentException("approvers must not be empty");
    }
    List<Approver> members = List.of(approvers);
    return context -> {
      ApprovalContext answering = new AnsweringOnly(context.request());
      for (Approver member : members) {
        ApprovalOutcome outcome = member.approve(answering);
        switch (outcome) {
          case ApprovalOutcome.Answered(Approval.Denied denied) -> {
            return new ApprovalOutcome.Answered(denied);
          }
          case ApprovalOutcome.Answered(Approval.Approved _) -> {
            // next member
          }
          case ApprovalOutcome.Deferred _ ->
              throw new IllegalStateException("allOf members must answer; one deferred");
        }
      }
      return new ApprovalOutcome.Answered(Approval.approved());
    };
  }

  /**
   * The marker the executor recognises to answer without building a request (spec §1.4). Sealed to
   * the two built-ins on purpose: a third implementor would skip enrichment for a call that might
   * need it.
   */
  public sealed interface Static extends Approver permits Allow, Deny {
    Approval answer();
  }

  static final class Allow implements Static {
    static final Allow INSTANCE = new Allow();

    @Override
    public Approval answer() {
      return Approval.approved();
    }

    @Override
    public ApprovalOutcome approve(ApprovalContext context) {
      return new ApprovalOutcome.Answered(answer());
    }
  }

  static final class Deny implements Static {
    private final Approval denied;

    Deny(Approval denied) {
      this.denied = denied;
    }

    @Override
    public Approval answer() {
      return denied;
    }

    @Override
    public ApprovalOutcome approve(ApprovalContext context) {
      return new ApprovalOutcome.Answered(denied);
    }
  }

  /**
   * The context {@link #allOf} hands its members. Nothing is withheld any more — a context is only
   * the frozen question now (spec §7) — so a member that means to park is refused where it always
   * mattered: at {@link #allOf}'s own switch, when it returns a deferral.
   */
  private record AnsweringOnly(ApprovalRequest request) implements ApprovalContext {}
}
