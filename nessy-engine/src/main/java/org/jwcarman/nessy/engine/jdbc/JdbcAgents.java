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

package org.jwcarman.nessy.engine.jdbc;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.agent.Agents;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The row that says an agent exists, as {@code nessy_agent}.
 *
 * <p>An agent comes into being the first time anything is said to it -- there is nothing to record
 * about one before its first input, and a create step would only be a way to get that wrong.
 *
 * <p><b>Why a row rather than a lock over the work itself.</b> Locking the backlog rows would leave
 * two arrivals to an empty backlog with nothing to contend for: both would find nothing, both would
 * insert, and neither would have excluded the other. A row that always exists is the thing to
 * contend for -- {@link JdbcRowLocks} is what takes it.
 *
 * <p><b>Sealing takes the backlog with it.</b> Ending an agent and abandoning what it was waiting
 * on are the same fact from two tables, so {@link #seal} clears {@code nessy_agent_backlog} for
 * this agent in the same statement that marks {@code nessy_agent}, and reports how many rows that
 * emptied.
 */
public final class JdbcAgents implements Agents {

  private static final String ENSURE =
      """
      INSERT INTO nessy_agent (agent_type, agent_id)
      VALUES (?, ?)
          ON CONFLICT (agent_type, agent_id) DO NOTHING
      """;

  private static final String TERMINATED =
      "SELECT terminated_at IS NOT NULL FROM nessy_agent WHERE agent_type = ? AND agent_id = ?";

  private static final String CLEAR_BACKLOG =
      "DELETE FROM nessy_agent_backlog WHERE agent_type = ? AND agent_id = ?";

  private static final String SEAL =
      """
      UPDATE nessy_agent
         SET terminated_at = COALESCE(terminated_at, now())
       WHERE agent_type = ? AND agent_id = ?
      """;

  private final JdbcClient jdbc;

  public JdbcAgents(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void ensure(AgentType agentType, AgentId agent) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    jdbc.sql(ENSURE).params(agentType.value(), agent.value()).update();
  }

  @Override
  public boolean terminated(AgentType agentType, AgentId agent) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return jdbc.sql(TERMINATED)
        .params(agentType.value(), agent.value())
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  @Override
  public int seal(AgentType agentType, AgentId agent) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    int abandoned = jdbc.sql(CLEAR_BACKLOG).params(agentType.value(), agent.value()).update();
    jdbc.sql(SEAL).params(agentType.value(), agent.value()).update();
    return abandoned;
  }
}
