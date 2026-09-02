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
package org.jwcarman.nessy.engine.agent;

import java.util.List;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;

/**
 * What happened. Facts, never provenance.
 *
 * <p>The same event used to have two names depending on who delivered it — {@code Ran} against
 * {@code RelayResult}, {@code Answered} against {@code RelayApproval} — because a child actor
 * replying and a reply token arriving took different paths. The agent has no reason to care: {@link
 * ToolCompleted} is identical whether a future finished in two milliseconds or a webhook answered
 * three days later.
 *
 * <p><b>Ids and small statuses only.</b> Whatever produces content checks it in BEFORE the agent is
 * told, so a state that says a call completed cannot reference a result that is not there. The line
 * is bounded against unbounded: a stop reason, a usage count and a reason somebody wrote to be read
 * travel inline; an asking message, a tool result and a rendered observation are claimed.
 */
public sealed interface Input {

  /**
   * Something changed in the backlog.
   *
   * <p>Carries nothing, on purpose. Its whole value is that it is droppable: a busy agent ignores
   * it, because going idle always ends with a take, and duplicates are free because a take against
   * an empty backlog is a no-op. Give it a payload and it stops being either.
   */
  record BacklogUpdated() implements Input {}

  /** The store handed over a row: its id is the turn id, and its claim holds the rendered input. */
  record WorkTaken(TurnId turnId, String observationClaim) implements Input {}

  /** The store had nothing waiting. */
  record NoWork() implements Input {}

  /** Fed on every activation, so recovery is the common path rather than a rare one. */
  record Recovered() implements Input {}

  /** The model answered. Its content is held; this says only what kind of answer it was. */
  sealed interface ModelAnswered extends Input {

    /** Prose. Held under {@code answer}. */
    record Answered(StopReason stopReason, Usage usage) implements ModelAnswered {}

    /** Tool calls. The asking message is held under {@code asked}. */
    record Asked(List<CallSummary> calls, Usage usage) implements ModelAnswered {
      public Asked {
        calls = List.copyOf(calls);
      }
    }

    /** A safety classifier declined. The explanation is short and written to be read. */
    record Refused(String category, String explanation, Usage usage) implements ModelAnswered {}
  }

  /** Bounded: an id and a name, which is all the logic needs to decide what to dispatch. */
  record CallSummary(CallId callId, String toolName) {}

  /** The call did not happen — a rate limit, a timeout, a connection reset. */
  record ModelFailed(String reason) implements Input {}

  /** The approver answered. The reason inside is short prose a person wrote. */
  record ApprovalGiven(CallId callId, String toolName, ApprovalResult result) implements Input {}

  /** The tool will answer later; someone holds a reply token. */
  record ToolParked(CallId callId, java.time.Instant expiresAt) implements Input {}

  /** The tool answered, whoever asked and however long it took. Its result is in claims. */
  record ToolCompleted(CallId callId) implements Input {}

  /**
   * Time ran out on a call.
   *
   * <p>Distinct from {@link ToolCompleted} deliberately: the sweep knows time passed and does not
   * get to decide what that means. Whether a timeout is a denial, an error or a retry is policy,
   * and policy belongs where it is testable.
   */
  record DeadlinePassed(CallId callId) implements Input {}

  /**
   * An application is finished with this agent instance.
   *
   * <p>Cooperative, like an interrupt: this SETS a flag rather than deleting anything. An idle
   * agent acts on it at once; a busy one finishes its turn first.
   */
  record Forget() implements Input {}

  /** The idle linger elapsed with nothing to do. */
  record SleepNow() implements Input {}
}
