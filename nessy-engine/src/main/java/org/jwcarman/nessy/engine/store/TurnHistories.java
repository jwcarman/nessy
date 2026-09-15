package org.jwcarman.nessy.engine.store;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * Where a reader gets one agent's history.
 *
 * <p>The read side of the story, and the only side anything outside the engine touches. Curation is
 * an extension point -- what a model is shown is a decision applications make differently -- while
 * what gets written is not, so the two are separate interfaces rather than one that offers both to
 * everybody.
 */
public interface TurnHistories {

  /** This agent's history, and no other's. */
  TurnHistory forAgent(AgentType agentType, AgentId agentId);
}
