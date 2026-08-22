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
package org.jwcarman.nessy.api.tool;

import java.util.Objects;
import org.jwcarman.nessy.durable.ComputationId;

/**
 * Where one tool call's durable questions live (spec §10.9): stamped by the executor — the one
 * party that provably holds the scope — before the tool runs. The two derivations below are the
 * single site the address formulas exist at; anyone holding the coordinates re-derives the same
 * ids, which is the submit-once discipline's foundation and lets external systems dedup on them.
 *
 * @param agentType the recipe's name
 * @param agentId the scope
 * @param responseId the committed model response that produced this call (durable-deliveries spec
 *     §2) — closes the provider-uniqueness hole a bare {@code callId} leaves open, since provider
 *     call ids are not contractually unique over an agent's lifetime
 * @param callId the provider-assigned tool call id
 */
public record CallAddress(String agentType, String agentId, String responseId, String callId) {

  public CallAddress {
    requireText(agentType, "agentType");
    requireText(agentId, "agentId");
    requireText(responseId, "responseId");
    requireText(callId, "callId");
  }

  /** The address of "may it run?" — completed with a {@code Decision} by the approval desk. */
  public ComputationId approval() {
    return ComputationId.of(
        "approval:%s:%s:%s:%s".formatted(agentType, agentId, responseId, callId));
  }

  /** The address of "what did it return?" — completed with a {@code ToolResult}. */
  public ComputationId execution() {
    return ComputationId.of("tool:%s:%s:%s:%s".formatted(agentType, agentId, responseId, callId));
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
