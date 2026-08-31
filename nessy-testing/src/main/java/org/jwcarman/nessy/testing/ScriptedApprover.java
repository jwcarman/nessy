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

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalContext;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/**
 * An approver that answers from a script, like {@link ScriptedModel}; when the script runs out it
 * defers.
 *
 * <p>This is how a binding's authorization decision gets tested without a real desk and without a
 * real person. It also records every request it was handed, oldest first, so a test can assert on
 * what the harness <em>asked</em>.
 *
 * <p>Thread-safe. A turn with several tool calls seeks approval for each of them on its own
 * executor thread, so {@link #approve} runs concurrently: the script is a {@link
 * ConcurrentLinkedDeque} and the request log a {@link CopyOnWriteArrayList}. Which thread gets
 * which scripted answer is therefore unspecified for a multi-call turn — the deque hands them out
 * in order, but the order the threads arrive in is not; a test that needs a particular answer for a
 * particular call should script one answer, or match on the request.
 */
public final class ScriptedApprover implements Approver {

  /**
   * How long a deferral this approver hands out lasts. Long enough that no test races it, short
   * enough that it is obviously a test value rather than a production term.
   */
  private static final Duration LEASE = Duration.ofHours(1);

  private final Deque<ApprovalResult> answers;
  private final List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();

  private ScriptedApprover(Deque<ApprovalResult> answers) {
    this.answers = answers;
  }

  /** Scripts a fixed sequence of answers, given out in order, one per {@link #approve} call. */
  public static ScriptedApprover answering(ApprovalResult... answers) {
    Objects.requireNonNull(answers, "answers must not be null");
    return new ScriptedApprover(new ConcurrentLinkedDeque<>(List.of(answers)));
  }

  /** An empty script: every call defers immediately. */
  public static ScriptedApprover deferring() {
    return answering();
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request, ApprovalContext context) {
    Objects.requireNonNull(request, "request must not be null");
    requests.add(request);
    ApprovalResult next = answers.poll();
    return next == null ? Awaited.deferred(Instant.now().plus(LEASE)) : Awaited.ready(next);
  }

  /** A snapshot of every request this approver was handed, oldest first. */
  public List<ApprovalRequest> requests() {
    return List.copyOf(requests);
  }
}
