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
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.effect.EffectHandlers;
import org.jwcarman.nessy.engine.effect.EffectTerms;

/**
 * One agent type's effects.
 *
 * <p>Holds the two things {@link JdbcEffectStore} deliberately does not: which agent type these
 * rows belong to, and what this agent type's effects are worth. So a caller says "store this
 * effect" and nothing else -- the timeout, the budget and the outcome to fall back on are looked up
 * here, from the binding, at the moment the row is written.
 *
 * <p>That is where the lookup belongs. The fold decides that an effect is <em>owed</em>; how long
 * it may run is configuration, and making the state machine carry configuration down to the table
 * is how a switch over effect kinds ends up living on the harness.
 */
public class EffectStore {

  private final AgentType agentType;
  private final EffectHandlers handlers;
  private final JdbcEffectStore rows;

  public EffectStore(AgentType agentType, EffectHandlers handlers, JdbcEffectStore rows) {
    this.agentType = agentType;
    this.handlers = handlers;
    this.rows = rows;
  }

  /**
   * Writes an effect down to be performed later.
   *
   * <p>Called from inside the fold's transaction, so the effect commits with the state change that
   * owed it or not at all.
   *
   * @param traceContext the trace this effect belongs to, as stored headers; null for none
   */
  public void insert(AgentId agentId, AgentEffect effect, Instant at, String traceContext) {
    EffectTerms terms = handlers.termsFor(effect);
    rows.insert(
        agentType,
        agentId,
        effect,
        terms.timeout(),
        terms.undispatchable(),
        at.plus(terms.timeout()),
        traceContext,
        at);
  }

  /** Every claimed, unfinished row of one agent -- the candidates a late answer could name. */
  public List<Attempt> runningFor(AgentId agentId) {
    return rows.runningFor(agentType, agentId);
  }

  /** Claims up to {@code batchSize} due effects of this agent type. */
  public List<Attempt> markRunning(Instant now, int batchSize) {
    return rows.markRunning(agentType, now, batchSize);
  }

  public boolean complete(UUID effectId, int attemptsMade) {
    return rows.complete(effectId, attemptsMade);
  }

  public boolean reschedule(UUID effectId, int attemptsMade, Instant at) {
    return rows.reschedule(effectId, attemptsMade, at);
  }

  public AgentEffect effectOf(Attempt attempt) {
    return rows.effectOf(attempt);
  }

  public EffectOutcome failureOf(Attempt attempt) {
    return rows.failureOf(attempt);
  }
}
