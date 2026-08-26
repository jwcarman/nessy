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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Facts, past tense. Six variants: every effect has exactly one completion event, {@code Observed}
 * is the sole inbound fact, and the two {@code *Deferred} events record a park (approval-lifecycle
 * spec §3).
 *
 * <p>The four that belong to one tool call form their own sealed sub-hierarchy, {@link
 * ToolCallEvent} (deferral-by-callback spec §3): a phase routes them by call id without naming any
 * of them individually, and a call's own state can never be handed the two that are not about a
 * call.
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

  /** The approver deferred: the ask is parked under {@code approval}, and this is the question. */
  record ApprovalDeferred(ToolCall call, ComputationId approval, ApprovalRequest request)
      implements ToolCallEvent {
    public ApprovalDeferred {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(approval, "approval must not be null");
      Objects.requireNonNull(request, "request must not be null");
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

  /** The tool deferred: its result is parked under {@code tool}. */
  record ToolDeferred(ToolCall call, ComputationId tool) implements ToolCallEvent {
    public ToolDeferred {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(tool, "tool must not be null");
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
