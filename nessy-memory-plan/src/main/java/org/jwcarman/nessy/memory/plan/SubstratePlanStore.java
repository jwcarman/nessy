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
package org.jwcarman.nessy.memory.plan;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * A plan kept in the substrate: one document per agent, replaced whole.
 *
 * <p>No versioned read-modify-write, unlike the notebook, because there is nothing to merge — the
 * model sends the entire list every time it changes anything, so a write is a replacement and the
 * last one is the truth. That is also what makes a durable re-drive safe: replaying the same
 * wholesale write stores the identical plan.
 */
public final class SubstratePlanStore implements PlanStore {

  private final DocumentStore<Plan> plans;

  public SubstratePlanStore(Substrate substrate, AgentType agentType) {
    Objects.requireNonNull(substrate, "substrate must not be null");
    Objects.requireNonNull(agentType, "agentType must not be null");
    this.plans = substrate.document("plan/" + agentType.name(), Plan.class);
  }

  @Override
  public Optional<Plan> find(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    // An empty plan reads as no plan: "cleared" and "never written" are one state, and nothing
    // downstream can tell them apart anyway.
    return plans.read(agentId.value()).map(Versioned::value).filter(plan -> !plan.isEmpty());
  }

  @Override
  public void save(AgentId agentId, Plan plan) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    plans.update(agentId.value(), Plan.empty(), current -> plan);
  }
}
