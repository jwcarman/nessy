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
package org.jwcarman.nessy.backend.effect;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * An effect about to be written down, with everything its row is born with.
 *
 * <p>Pending because that is where every row starts: nobody has tried it yet. {@link
 * Effects#insert} takes one of these and gives the row its own id.
 *
 * @param agentType the type of the agent that owes it
 * @param agentId the agent that owes it
 * @param effect what is owed
 * @param timeout how long one attempt is believed before the row comes due again
 * @param undispatchable the outcome to deliver if the effect can never be performed
 * @param deadline when the row stops being worth trying
 * @param traceContext the trace the effect belongs to, as stored headers; null for none
 * @param at when it is written down, which is also when it first comes due
 */
public record PendingEffect(
    AgentType agentType,
    AgentId agentId,
    AgentEffect effect,
    Duration timeout,
    EffectOutcome undispatchable,
    Instant deadline,
    String traceContext,
    Instant at) {

  public PendingEffect {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    Objects.requireNonNull(undispatchable, "undispatchable must not be null");
    Objects.requireNonNull(deadline, "deadline must not be null");
    Objects.requireNonNull(at, "at must not be null");
  }
}
