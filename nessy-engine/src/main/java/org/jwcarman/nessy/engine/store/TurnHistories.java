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
