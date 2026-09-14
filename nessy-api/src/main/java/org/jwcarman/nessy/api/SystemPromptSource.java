package org.jwcarman.nessy.api;

/**
 * What an agent is told about itself, worked out per agent.
 *
 * <p>Resolved when a turn's inference runs, on the dispatcher's own thread while the request is
 * being built -- not inside the fold and not under the agent's row lock. So this one may do I/O:
 * look up a tenant, read a feature flag, ask what today is. It sits on the critical path of every
 * inference, which is a reason to keep it quick, not a reason to keep it pure.
 *
 * <p>Per agent because a harness is one agent type serving many agents, and "you are helping Acme
 * Ltd" is the ordinary kind of thing to want.
 */
@FunctionalInterface
public interface SystemPromptSource {

  SystemPrompt forAgent(AgentId agentId);

  /** The same words for every agent. */
  static SystemPromptSource constant(SystemPrompt prompt) {
    return _ -> prompt;
  }
}
