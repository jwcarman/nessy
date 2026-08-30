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
package org.jwcarman.nessy.engine;

import java.util.Objects;

/**
 * One turn, as the turn itself persists it.
 *
 * <p><b>Keyed by AGENT, not by turn</b>, and overwritten as each new turn begins. The Java DSL has
 * no delete effect for durable state, so a document per turn would accumulate one row per
 * observation forever; and deleting out of band — through the store API, outside the effect
 * pipeline — is an async call that can fail with nothing to retry it, leaking rows no sweep would
 * find. Overwriting makes cleanup a side effect of doing work, which cannot be forgotten.
 *
 * <p>{@code turnId} is therefore load-bearing: it says WHICH turn this document describes. A
 * respawned turn actor compares it against the id the agent gave it, and a document naming a
 * different turn is a stale predecessor's, to be overwritten rather than resumed.
 */
public record TurnState(String turnId, Phase phase) {

  public TurnState {
    Objects.requireNonNull(phase, "phase must not be null");
  }

  public static TurnState idle() {
    return new TurnState(null, new Phase.Starting());
  }

  public TurnState at(Phase next) {
    return new TurnState(turnId, next);
  }

  /** Whether this document describes {@code candidate} rather than some earlier turn. */
  public boolean describes(String candidate) {
    return turnId != null && turnId.equals(candidate);
  }
}
