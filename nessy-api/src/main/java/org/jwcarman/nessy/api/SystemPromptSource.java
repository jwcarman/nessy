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
