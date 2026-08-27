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
package org.jwcarman.nessy.spike.pekko;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * THROWAWAY SPIKE. Where one tool call stands.
 *
 * <p>{@code AwaitingApproval} is the park: no thread, no timer, no lease, no outbox row — the call
 * simply sits in the durable state until somebody sends {@link SpikeTurnEntity.AnswerApproval} to
 * this agent's entity id. The entity id IS the callback address.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "phase")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SpikeCallPhase.AwaitingApproval.class, name = "awaiting-approval"),
  @JsonSubTypes.Type(value = SpikeCallPhase.Running.class, name = "running"),
  @JsonSubTypes.Type(value = SpikeCallPhase.Finished.class, name = "finished"),
  @JsonSubTypes.Type(value = SpikeCallPhase.Denied.class, name = "denied")
})
public sealed interface SpikeCallPhase extends SpikeSerializable {

  /** True once this call owes the turn nothing more. */
  boolean settled();

  /** Parked on a human. Costs nothing while it waits. */
  record AwaitingApproval(String question) implements SpikeCallPhase {
    @Override
    public boolean settled() {
      return false;
    }
  }

  /** Approved, and the tool is executing somewhere off this actor's thread. */
  record Running() implements SpikeCallPhase {
    @Override
    public boolean settled() {
      return false;
    }
  }

  record Finished(String result) implements SpikeCallPhase {
    @Override
    public boolean settled() {
      return true;
    }
  }

  record Denied(String reason) implements SpikeCallPhase {
    @Override
    public boolean settled() {
      return true;
    }
  }
}
