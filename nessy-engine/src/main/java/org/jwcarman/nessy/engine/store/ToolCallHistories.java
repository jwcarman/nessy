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
