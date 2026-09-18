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

import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.engine.agent.Decision;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * {@link HistoryStore} for one agent type: its name, its renderer, and the shared rows underneath.
 *
 * @param <O> the observation type
 */
public class AgentHistoryStore<O> implements HistoryStore<O> {

  private final AgentType agentType;
  private final ObservationRenderer<O> renderer;
  private final JdbcHistoryStore rows;

  public AgentHistoryStore(
      AgentType agentType, ObservationRenderer<O> renderer, JdbcHistoryStore rows) {
    this.agentType = agentType;
    this.renderer = renderer;
    this.rows = rows;
  }

  @Override
  public List<Appended> append(AgentId agentId, List<HistoryEntry> entries) {
    return rows.append(agentType, agentId, entries);
  }

  @Override
  public Appended open(AgentId agentId, Decision.Opening<O> opening) {
    HistoryEntry.ObservationReceived entry =
        HistoryEntry.ObservationReceived.opening(
            opening.seq(), renderer.render(opening.observation()));
    return rows.append(agentType, agentId, List.of(entry)).getFirst();
  }
}
