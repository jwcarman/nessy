package org.jwcarman.nessy.engine.store;

import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.tool.ToolCalls;

/**
 * Hands out a view of history that answers one question: what call is at this address?
 *
 * <p>Separate from {@link TurnHistories} because it is a different way of reading the same rows.
 * That port hands out conversations to whoever is building a request; this one hands out single
 * entries to whoever is holding an effect and needs the one fact it names. Folding both into one
 * interface would offer every reader the reading they have no business doing.
 */
public interface ToolCallHistories {

  /** This history, narrowed to one agent type. */
  ToolCalls forAgentType(AgentType agentType);
}
