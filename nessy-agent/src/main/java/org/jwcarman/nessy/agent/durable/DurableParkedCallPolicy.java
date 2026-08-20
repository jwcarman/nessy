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
import java.util.Optional;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.DurableOutcomes;
import org.jwcarman.nessy.agent.ScopeResumption;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.ParkedCallPolicy;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.AwaitResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.DurableComputationBackend;

/**
 * The durable wiring's answer to a park (§4.3): get-or-create the slot at its deterministic id
 * (submit-once — a recovery re-fire finds it, ruling 4), await atomically, and only then hand the
 * desk the token — if the answer already arrived, nothing is registered. Registered means
 * suspended; AlreadyCompleted means the answer arrived while we were away — deliver it now.
 */
public final class DurableParkedCallPolicy implements ParkedCallPolicy {

  private final DurableComputationBackend backend;
  private final ApprovalDesk desk;
  private final AgentType type;
  private final AgentId id;

  public DurableParkedCallPolicy(
      DurableComputationBackend backend, ApprovalDesk desk, AgentType type, AgentId id) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.desk = Objects.requireNonNull(desk, "desk must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.id = Objects.requireNonNull(id, "id must not be null");
  }

  @Override
  public Optional<ToolOutcome> onParked(ToolCall call, ParkToken token) {
    ComputationId slotId =
        ComputationId.of("tool:%s:%s:%s".formatted(type.name(), id.value(), call.id()));
    backend.create(slotId);
    return switch (backend.await(slotId, ScopeResumption.continuationFor(type, id, call))) {
      case AwaitResult.AlreadyCompleted(var outcome) ->
          Optional.of(DurableOutcomes.toToolOutcome(outcome));
      case AwaitResult.Registered() -> {
        desk.register(token, slotId);
        yield Optional.empty();
      }
    };
  }
}
