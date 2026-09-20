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
package org.jwcarman.nessy.engine.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.spi.inference.InferenceRequest;

/**
 * One model call as the model saw it: the request as rendered, and how it came back.
 *
 * @param turn the open turn the call was made for
 * @param usage what the call cost, once it returned and if the vendor said
 * @param outcome {@code answer}, {@code actions}, {@code refusal} or {@code fault}; empty while the
 *     call is in flight, or forever if the process died before it returned
 */
public record RecordedInference(
    UUID id,
    AgentType agentType,
    AgentId agentId,
    TurnId turn,
    Instant requestedAt,
    InferenceRequest request,
    Optional<String> outcome,
    Optional<Instant> completedAt,
    Optional<Usage> usage) {

  public RecordedInference {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(turn, "turn must not be null");
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(completedAt, "completedAt must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
  }

  public boolean completed() {
    return completedAt.isPresent();
  }
}
