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
package org.jwcarman.nessy.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.tool.ComputationCallback;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Facts, past tense. Every effect has exactly one completion event and {@code Observed} is the sole
 * inbound fact.
 *
 * <p>A deferral takes TWO facts, not one (deferral-by-callback spec §9a). {@code
 * …DeferralRequested} is what the deferring party asked for: a callback and a term, and no
 * computation id, because at that moment no computation exists. {@code …Deferred} is the park
 * itself: the id the plumbing minted and the deadline it agreed, folded only once the callback has
 * run. Nothing outside knows the id until the second fact, which is what makes re-firing the
 * ORIGINATING step safe in between.
 *
 * <p>The six that belong to one tool call form their own sealed sub-hierarchy, {@link
 * ToolCallEvent} (spec §3): a phase routes them by call id without naming any of them individually,
 * and a call's own state can never be handed the two that are not about a call.
 */
public sealed interface AgentEvent
    permits AgentEvent.Observed, AgentEvent.ModelFinished, ToolCallEvent {

  record Observed(List<ContentBlock> content) implements AgentEvent {
    public Observed {
      Objects.requireNonNull(content, "content must not be null");
      content = List.copyOf(content);
    }
  }

  record ModelFinished(ModelOutcome outcome) implements AgentEvent {
    public ModelFinished {
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }

  /**
   * The approver returned a deferral. No id yet — nothing has been created, so nothing outside can
   * know anything (spec §9a). The callback rides the fact only so the {@code DeferApproval} effect
   * it produces can carry it; no STATE ever holds it (spec §4, James 2026-08-26).
   *
   * @param request the frozen question, travelling with the deferral because the approval
   *     computation's continuation is built from it and re-running the enrichers would build a
   *     different one
   * @param term how long the approver wants the question to stand, before the harness clips it
   */
  record ApprovalDeferralRequested(
      ToolCall call, ApprovalRequest request, ComputationCallback callback, Duration term)
      implements ToolCallEvent {
    public ApprovalDeferralRequested {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(request, "request must not be null");
      Objects.requireNonNull(callback, "callback must not be null");
      Objects.requireNonNull(term, "term must not be null");
    }
  }

  /**
   * The ask is parked under {@code approval} and the callback has run: this fact IS the
   * notification landing (spec §9a — there is no separate {@code Notified} event).
   *
   * @param deadline what was actually agreed, so the pending-approvals projection can show it (spec
   *     §5) — Continuum has no read door, and a deadline the fold does not carry is one nothing
   *     downstream can ever learn
   */
  record ApprovalDeferred(
      ToolCall call, ComputationId approval, ApprovalRequest request, Instant deadline)
      implements ToolCallEvent {
    public ApprovalDeferred {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(approval, "approval must not be null");
      Objects.requireNonNull(request, "request must not be null");
      Objects.requireNonNull(deadline, "deadline must not be null");
    }
  }

  /** An answer: in-process ({@code approval} empty) or delivered from a parked computation. */
  record ApprovalAnswered(ToolCall call, Optional<ComputationId> approval, Approval answer)
      implements ToolCallEvent {
    public ApprovalAnswered {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(approval, "approval must not be null");
      Objects.requireNonNull(answer, "answer must not be null");
    }
  }

  /** The tool side's {@link ApprovalDeferralRequested}: a callback and a term, and no id yet. */
  record ToolCallDeferralRequested(ToolCall call, ComputationCallback callback, Duration term)
      implements ToolCallEvent {
    public ToolCallDeferralRequested {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(callback, "callback must not be null");
      Objects.requireNonNull(term, "term must not be null");
    }
  }

  /** The tool side's {@link ApprovalDeferred}: the result is parked under {@code tool}. */
  record ToolCallDeferred(ToolCall call, ComputationId tool, Instant deadline)
      implements ToolCallEvent {
    public ToolCallDeferred {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(tool, "tool must not be null");
      Objects.requireNonNull(deadline, "deadline must not be null");
    }
  }

  /** A result: in-process ({@code tool} empty) or delivered from a parked computation. */
  record ToolFinished(ToolCall call, Optional<ComputationId> tool, ToolOutcome outcome)
      implements ToolCallEvent {
    public ToolFinished {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(tool, "tool must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }
}
