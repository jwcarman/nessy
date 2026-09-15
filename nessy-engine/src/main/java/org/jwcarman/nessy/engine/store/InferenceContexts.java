package org.jwcarman.nessy.engine.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * What each model call was shown, read-only: the evidence a trajectory, an eval or a critic is
 * built from, and the first thing to look at when a call went wrong.
 */
public interface InferenceContexts {

  /** Every call made for this agent, oldest first. */
  List<RecordedInference> forAgent(AgentType agentType, AgentId agentId);

  Optional<RecordedInference> find(UUID id);
}
