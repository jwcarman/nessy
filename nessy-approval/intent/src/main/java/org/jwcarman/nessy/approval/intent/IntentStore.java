package org.jwcarman.nessy.approval.intent;

import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;

/**
 * What an agent most recently said it was about to do.
 *
 * <p><b>Keyed per call, not per instance.</b> A tool is bound once to a harness that serves every
 * agent of its type, so a store built for one agent could only ever serve one agent. The id arrives
 * on every call now, and this is the same shape the notebook and the plan settled on: one store,
 * many agents, the agent named on the way in.
 */
public interface IntentStore<T> {

  void declare(AgentId agentId, T declaration);

  Optional<T> latest(AgentId agentId);
}
