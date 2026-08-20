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
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.CompletionResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.Outcome;

/**
 * The approve/deny doors (§4.3), addressed by the computation's own deterministic identity — the
 * desk holds no state of its own, because the backend is the state. Re-drives re-derive the same
 * id, so there is exactly one handle per question, ever. Unknown ids and second decisions are
 * refused loudly by the backend's own vocabulary. Completion-capability secrets (durable spec §9,
 * "MAY be secured separately") arrive with the out-of-process doors in Plan 5.
 *
 * <p>Complete-then-fire, at-least-once (plan decision 3): a handler throw during fire propagates
 * with the slot already terminal — the lazy re-drive floor covers delivery, and the Plan-5 outbox
 * is the prompt-delivery upgrade.
 */
public final class ApprovalDesk {

  private final DurableComputationBackend backend;
  private final ContinuationDispatcher dispatcher;

  public ApprovalDesk(DurableComputationBackend backend, ContinuationDispatcher dispatcher) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
  }

  public void approve(ComputationId id, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    decide(id, new Outcome.Success(result));
  }

  public void deny(ComputationId id, String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    decide(id, new Outcome.Failure(reason));
  }

  private void decide(ComputationId id, Outcome outcome) {
    Objects.requireNonNull(id, "id must not be null");
    if (backend.complete(id, outcome) == CompletionResult.ALREADY_TERMINAL) {
      throw new IllegalStateException("already decided: " + id.value());
    }
    dispatcher.fire(backend.continuationsOf(id), outcome);
  }
}
