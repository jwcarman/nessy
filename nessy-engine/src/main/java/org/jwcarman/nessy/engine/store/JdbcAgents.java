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

import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The row that says an agent exists, and the lock everything else about it is taken under.
 *
 * <p>An agent comes into being the first time anything is said to it -- there is nothing to record
 * about one before its first input, and a create step would only be a way to get that wrong.
 *
 * <p><b>Why a row rather than a lock over the work itself.</b> Locking the backlog rows would leave
 * two arrivals to an empty backlog with nothing to contend for: both would find nothing, both would
 * insert, and neither would have excluded the other. A row that always exists is the thing to
 * contend for.
 *
 * <p><b>Why a lock rather than a lease.</b> It is held for exactly the transaction and released by
 * the database when a connection dies -- so there is no time-to-live to tune, and none of the
 * trouble a lease has telling a slow holder from a dead one. That works here and not on the direct
 * door, whose turn spans an inference: holding a transaction open across a model call is not a
 * thing to do.
 */
public final class JdbcAgents {

  private static final String ENSURE =
      """
      INSERT INTO nessy_agent (agent_type, agent_id)
      VALUES (?, ?)
          ON CONFLICT (agent_type, agent_id) DO NOTHING
      """;

  private static final String LOCK =
      """
      SELECT terminated_at IS NOT NULL
        FROM nessy_agent
       WHERE agent_type = ? AND agent_id = ?
         FOR UPDATE
      """;

  private final JdbcClient jdbc;

  public JdbcAgents(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * Takes this agent for the rest of the transaction, bringing it into being if it is new.
   *
   * <p>Blocks until whoever else holds it commits or rolls back. Everything the caller then does --
   * reading the last turn, coalescing, appending, handing over to the next turn -- happens with the
   * agent to itself.
   *
   * @return whether this agent has been told to end. A caller that is told so must not coalesce:
   *     anything put into an emptied backlog would be read as work next time and undo a termination
   *     that had already happened.
   */
  public boolean lock(AgentType agentType, AgentId agent) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    jdbc.sql(ENSURE).params(agentType.value(), agent.value()).update();
    return jdbc.sql(LOCK).params(agentType.value(), agent.value()).query(Boolean.class).single();
  }
}
