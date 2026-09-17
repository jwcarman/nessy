package org.jwcarman.nessy.engine.effect;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.engine.agent.EffectOutcome;

/**
 * How a performed effect gets back into the agent it was performed for.
 *
 * <p>Separate from {@code Harness} on purpose. The harness is the door callers hold, and its only
 * method is {@code observe}; if delivering an outcome were on it too, any caller could invent an
 * answer the model never gave and fold it into an agent's story. Splitting the two means the
 * capability exists for the machinery that has earned it and is not on the type a user is handed.
 *
 * <p>The implementation is the harness -- the fold has to happen behind the same row lock an
 * observation takes -- but nobody coding against a harness ever sees that.
 */
public interface AgentEffectCallback {

  /**
   * Folds an outcome into the agent that owed the effect.
   *
   * <p>Called after the work happened and before the effect row is retired, so a crash between the
   * two leaves a row that comes due again -- and the fold, seeing no call outstanding, ignores the
   * second delivery rather than answering twice.
   *
   * @param traceContext the trace of the effect this answers, so whatever the outcome causes stays
   *     in the same turn's trace; null when that effect had none
   */
  void deliverOutcome(AgentId agentId, EffectOutcome outcome, String traceContext);
}
