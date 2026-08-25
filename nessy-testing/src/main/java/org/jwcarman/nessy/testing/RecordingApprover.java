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
package org.jwcarman.nessy.testing;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;

/**
 * A thin wrapper around any {@link Approver}, recording every (request, outcome) pair it produced,
 * oldest first — the approval-vocabulary counterpart of wrapping a {@code Memory} to watch what it
 * remembers.
 */
public final class RecordingApprover implements Approver {

  /** One decision this approver's delegate made: the question, and how it answered. */
  public record Decision(ApprovalRequest request, ApprovalOutcome outcome) {
    public Decision {
      Objects.requireNonNull(request, "request must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }

  private final Approver delegate;
  private final List<Decision> decisions = new CopyOnWriteArrayList<>();

  public RecordingApprover(Approver delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

  @Override
  public ApprovalOutcome approve(ApprovalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    ApprovalOutcome outcome = delegate.approve(context);
    decisions.add(new Decision(context.request(), outcome));
    return outcome;
  }

  /** Every (request, outcome) pair this approver has seen, oldest first. */
  public List<Decision> decisions() {
    return List.copyOf(decisions);
  }

  /** Just the requests, oldest first — sugar over {@link #decisions()}. */
  public List<ApprovalRequest> requests() {
    return decisions.stream().map(Decision::request).toList();
  }
}
