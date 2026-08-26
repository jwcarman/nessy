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
import java.util.Objects;
import org.jwcarman.nessy.api.tool.ComputationCallback;

/**
 * What an approver returns (spec §1.3): decided, or parked — where parking is a pure RETURN
 * (deferral-by-callback spec §1), not a call into plumbing that folds from inside the ask.
 */
public sealed interface ApprovalOutcome {

  record Answered(Approval approval) implements ApprovalOutcome {
    public Answered {
      Objects.requireNonNull(approval, "approval must not be null");
    }
  }

  /**
   * "I'll get back to you."
   *
   * @param callback what to run once the approval computation exists — the only thing that tells a
   *     human there is a question, and the id they answer on
   * @param term how long the approver wants the question to stand; REQUIRED (spec §5), clipped by
   *     the harness to its own approval ceiling
   */
  record Deferred(ComputationCallback callback, Duration term) implements ApprovalOutcome {
    public Deferred {
      Objects.requireNonNull(callback, "callback must not be null");
      Objects.requireNonNull(term, "term must not be null");
    }
  }

  /** {@link Deferred}: what to do once the id exists, and for how long the question stands. */
  static ApprovalOutcome deferred(ComputationCallback callback, Duration term) {
    return new Deferred(callback, term);
  }
}
