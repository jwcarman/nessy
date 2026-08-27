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
import java.util.Objects;
import org.jwcarman.nessy.api.tool.ComputationCallback;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Commands, imperative (approval-lifecycle spec §3): {@code CallModel} is a bare marker — the
 * executor asks Memory for context itself, and a fat effect could not be re-derived by recovery
 * (spec §6.1). {@code SeekApproval} asks and never runs; {@code RunTool} runs and never asks — the
 * answer between them is always a folded fact.
 *
 * <p>The two deferral effects are the exception to re-derivability, and the ONLY place a {@link
 * ComputationCallback} ever lives (deferral-by-callback spec §4, James 2026-08-26). Effects are
 * dispatched in memory once the fold that produced them has committed, and are never persisted; a
 * closure therefore cannot be rebuilt by {@code outstanding()}, which is exactly why {@code
 * Deferring…} recovers by re-firing its ORIGINATING step instead of re-firing one of these.
 */
public sealed interface Effect {

  record CallModel() implements Effect {}

  /** Ask: yields {@code ApprovalAnswered} or {@code ApprovalDeferralRequested}. */
  record SeekApproval(ToolCall call) implements Effect {
    public SeekApproval {
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /** Run: yields {@code ToolFinished} or {@code ToolCallDeferralRequested}. */
  record RunTool(ToolCall call) implements Effect {
    public RunTool {
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /**
   * Hand the approval off, in this order and no other (spec §9a, ordering ruled 2026-08-26): create
   * the computation; clip {@code term} to the harness's approval ceiling; <b>fold {@code
   * ApprovalDeferred} and let it commit</b>; and only THEN run {@code callback} with the id and the
   * agreed deadline.
   *
   * <p>The fold must precede the callback because the callback is what tells the world where to
   * answer, and the world may answer instantly. Fold afterwards and that answer meets a call still
   * in {@code DeferringApproval}, which records no id — dropped permanently, and the call hangs on
   * a computation already completed.
   *
   * <p>A throwing callback yields a failure instead: we know it failed, not whether it reached the
   * world first, so the call fails rather than re-asking and risking telling the world twice. By
   * then the park has folded, so that failure rides the id the phase now names.
   */
  record DeferApproval(
      ToolCall call, ApprovalRequest request, ComputationCallback callback, Duration term)
      implements Effect {
    public DeferApproval {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(request, "request must not be null");
      Objects.requireNonNull(callback, "callback must not be null");
      Objects.requireNonNull(term, "term must not be null");
    }
  }

  /**
   * The tool side's {@link DeferApproval}, under the same ordering: fold {@code ToolCallDeferred}
   * and let it commit, then call. Yields the park, or a failure.
   */
  record DeferToolCall(ToolCall call, ComputationCallback callback, Duration term)
      implements Effect {
    public DeferToolCall {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(callback, "callback must not be null");
      Objects.requireNonNull(term, "term must not be null");
    }
  }
}
