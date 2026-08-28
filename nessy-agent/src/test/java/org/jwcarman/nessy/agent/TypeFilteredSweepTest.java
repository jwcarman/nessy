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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.agent.AgentType;

/**
 * Isolation by construction (computation-identity spec §3, superseding harness-first spec §5's
 * runtime type-filtered sweep): two harnesses of DIFFERENT {@link AgentType}s sharing one substrate
 * never share a kind — {@code tool/alpha} and {@code tool/beta} are different keyspaces, {@code
 * approval/alpha} and {@code approval/beta} likewise — so the string each {@link AgentType} derives
 * a kind from can never collide across types.
 *
 * <p>The runtime observables this file used to prove over the type-filtered sweep, and then over
 * the Substrate-outbox drain and reaper (both retired, continuum-adoption spec §6) — that a foreign
 * type's record survives untouched, that only the matching scope's memory grows, that only the
 * matching type's executor fires — no longer have a Nessy-owned mechanism to observe them through:
 * both kinds now live entirely inside Continuum's own {@code ContinuumClient} storage, claimed and
 * delivered by kind string with no Substrate scan on this side to instrument. Isolation there is
 * Continuum's own contract to keep, not this module's to reprove. What remains here — and is still
 * this module's own concern — is the one guarantee the kind strings themselves must uphold: no
 * {@link AgentType} name can produce a kind string that collides with another's.
 */
class TypeFilteredSweepTest {

  @Nested
  class AgentTypeValidation {

    /**
     * Kind-name hygiene (computation-identity spec §3): the type threads into kind strings ({@code
     * computation/<agentType>}, {@code approval/<agentType>}, {@code outbox/<agentType>}) — nothing
     * parses those apart anymore, but a colon in the type would still make a kind string look like
     * it carries a delimiter it does not. Rejecting it at construction, with a message naming the
     * offending value, catches the mistake at the door.
     */
    @Test
    void anAgentTypeNameContainingAColonIsRejectedWithATeachingMessage() {
      assertThatThrownBy(() -> AgentType.of("ops:eu"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ops:eu")
          .hasMessageContaining(":");
    }
  }
}
