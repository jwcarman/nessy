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
package org.jwcarman.nessy.agent.durable;

import java.util.Objects;
import java.util.function.Consumer;
import org.jwcarman.nessy.agent.ScopeRedrive;
import org.jwcarman.nessy.durable.AwaitResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.CreateResult;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.approval.Approver;

/**
 * The slot-backed adjudicator (spec §4.3 amendment): the approval slot IS the fact — absent means
 * never asked, pending means the question is open, terminal means decided. Create-then-await is
 * idempotent (submit-once), so a re-driven ask re-registers the same redrive continuation and never
 * re-notifies; the notifier fires exactly once, on the ask that created the slot. The notification
 * is the point-to-point capability handoff of §4.3 — one recipient, never narrated.
 */
public final class SlotApprover implements Approver {

  private final DurableComputationBackend backend;
  private final Consumer<ApprovalRequest> notifier;
  private final ScopeRedrive scopeRedrive;

  public SlotApprover(
      DurableComputationBackend backend,
      Consumer<ApprovalRequest> notifier,
      ScopeRedrive scopeRedrive) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    this.scopeRedrive = Objects.requireNonNull(scopeRedrive, "scopeRedrive must not be null");
  }

  @Override
  public Adjudication adjudicate(ApprovalRequest request) {
    ComputationId slot = request.address().approval();
    CreateResult created = backend.create(slot);
    AwaitResult awaited = backend.await(slot, scopeRedrive.continuationFor(request.address()));
    return switch (awaited) {
      case AwaitResult.AlreadyCompleted(var outcome) ->
          DurableDecisions.toAdjudication(outcome, slot);
      case AwaitResult.Registered() -> {
        if (created.created()) {
          notifier.accept(request);
        }
        yield new Adjudication.Suspended(slot);
      }
    };
  }
}
