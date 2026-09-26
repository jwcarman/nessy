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
package org.jwcarman.nessy.backend.agent;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * The row that says an agent exists, and whether it has been told to end.
 *
 * <p>What a backend implementer provides to be an agent's backend at all: an agent comes into being
 * the first time anything is said to it, may be told to end, and may be asked whether it already
 * has been. None of this is a backlog's business -- a backend that offers only a queue of waiting
 * inputs still has to answer these, because a harness needs the answer before it ever looks at what
 * is waiting.
 */
public interface Agents {

  /** Brings this agent into being if it is new. Idempotent -- safe to call every time. */
  void ensure(AgentType type, AgentId agent);

  /** Whether this agent has been told to end, whether or not it has noticed yet. */
  boolean terminated(AgentType type, AgentId agent);

  /**
   * Ends this agent, and abandons whatever it was waiting to do.
   *
   * <p><b>Nothing may be coalesced into a sealed agent afterwards.</b> An arrival that got past
   * this would put something back into an emptied backlog, and the next read would answer with an
   * item rather than end -- undoing a termination that had already happened.
   *
   * @return how many were abandoned, so that work thrown away is counted rather than vanishing
   */
  int seal(AgentType type, AgentId agent);
}
