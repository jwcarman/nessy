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
