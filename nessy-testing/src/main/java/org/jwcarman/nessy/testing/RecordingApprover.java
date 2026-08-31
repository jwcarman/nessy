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
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalContext;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/**
 * A thin wrapper around any {@link Approver}, recording every (request, answer) pair it produced,
 * oldest first — the approval-vocabulary counterpart of wrapping a {@code Memory} to watch what it
 * remembers.
 */
public final class RecordingApprover implements Approver {

  /** One answer this approver's delegate gave: the question, and how it answered. */
  public record Answer(ApprovalRequest request, Awaited<ApprovalResult> result) {
    public Answer {
      Objects.requireNonNull(request, "request must not be null");
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  private final Approver delegate;
  private final List<Answer> answers = new CopyOnWriteArrayList<>();

  public RecordingApprover(Approver delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request, ApprovalContext context) {
    Objects.requireNonNull(request, "request must not be null");
    Awaited<ApprovalResult> result = delegate.approve(request, context);
    answers.add(new Answer(request, result));
    return result;
  }

  /** Every (request, answer) pair this approver has seen, oldest first. */
  public List<Answer> answers() {
    return List.copyOf(answers);
  }

  /** Just the requests, oldest first — sugar over {@link #answers()}. */
  public List<ApprovalRequest> requests() {
    return answers.stream().map(Answer::request).toList();
  }
}
