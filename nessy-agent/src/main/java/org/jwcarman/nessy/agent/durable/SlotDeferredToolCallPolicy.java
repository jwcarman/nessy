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
import org.jwcarman.nessy.agent.DurableOutcomes;
import org.jwcarman.nessy.agent.ScopeResumption;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.AwaitResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.DurableComputationBackend;

/**
 * The durable wiring's answer to a deferral (§4.3): get-or-create the slot at its deterministic id
 * (submit-once — a recovery re-fire finds the same slot, ruling 4) and await atomically. Registered
 * means suspended; AlreadyCompleted means the answer arrived while we were away — deliver it now.
 * The deterministic id is the one handle per question. Completion-capability secrets (durable spec
 * §9, "MAY be secured separately") arrive with the out-of-process doors in Plan 5.
 */
public final class SlotDeferredToolCallPolicy implements DeferredToolCallPolicy {

  private final DurableComputationBackend backend;
  private final ScopeResumption scopeResumption;

  public SlotDeferredToolCallPolicy(
      DurableComputationBackend backend, ScopeResumption scopeResumption) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.scopeResumption =
        Objects.requireNonNull(scopeResumption, "scopeResumption must not be null");
  }

  @Override
  public ToolExecution onDeferred(ToolCall call, CallAddress address) {
    ComputationId slotId = address.execution();
    backend.create(slotId);
    return switch (backend.await(slotId, scopeResumption.continuationFor(address, call))) {
      case AwaitResult.AlreadyCompleted(var outcome) ->
          new ToolExecution.Immediate(DurableOutcomes.toToolOutcome(outcome));
      case AwaitResult.Registered() -> new ToolExecution.Deferred(slotId);
    };
  }
}
