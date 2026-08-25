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

import java.util.List;
import java.util.Objects;

/** The built-in approvers and the two compositions people reach for (spec §1.4). */
public final class Approvers {

  private Approvers() {}

  /** Every call runs; no request is built (the executor's rung-0 fast path). */
  public static Approver allow() {
    return Allow.INSTANCE;
  }

  /** Every call is refused with {@code reason}; no request is built. */
  public static Approver deny(String reason) {
    return new Deny(Approval.denied(reason));
  }

  /** Every call is parked for someone else to answer; nobody is told. */
  public static Approver defer() {
    return ApprovalContext::defer;
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
            return context.defer();
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

  /** The context {@link #allOf} hands its members: the same request, a door that refuses. */
  private record AnsweringOnly(ApprovalRequest request) implements ApprovalContext {
    @Override
    public ApprovalOutcome defer() {
      throw new IllegalStateException("allOf members must answer; defer() is not available here");
    }
  }
}
