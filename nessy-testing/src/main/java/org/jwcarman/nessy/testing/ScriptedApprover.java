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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;

/**
 * An approver that answers from a script, like {@code ScriptedModel}; when the script runs out it
 * defers.
 *
 * <p>This is how a grant's authorization decision gets tested without a real desk, a real person,
 * or a real Continuum standing behind it. It also records every request it was handed, oldest
 * first, so a test can assert on what the harness <em>asked</em>.
 */
public final class ScriptedApprover implements Approver {

  private final Deque<Approval> answers;
  private final List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();

  private ScriptedApprover(Deque<Approval> answers) {
    this.answers = answers;
  }

  /** Scripts a fixed sequence of answers, given out in order, one per {@link #approve} call. */
  public static ScriptedApprover answering(Approval... answers) {
    Objects.requireNonNull(answers, "answers must not be null");
    return new ScriptedApprover(new ArrayDeque<>(List.of(answers)));
  }

  /** An empty script: every call defers immediately. */
  public static ScriptedApprover deferring() {
    return answering();
  }

  @Override
  public ApprovalOutcome approve(ApprovalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    requests.add(context.request());
    Approval next = answers.poll();
    return next == null ? context.defer() : new ApprovalOutcome.Answered(next);
  }

  /** A snapshot of every request this approver was handed, oldest first. */
  public List<ApprovalRequest> requests() {
    return List.copyOf(requests);
  }
}
