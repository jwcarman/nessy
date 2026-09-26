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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The row that says an agent exists.
 *
 * <p>An agent comes into being the first time anything is said to it -- there is nothing to record
 * about one before its first input, and a create step would only be a way to get that wrong.
 *
 * <p><b>Why a row rather than a lock over the work itself.</b> Locking the backlog rows would leave
 * two arrivals to an empty backlog with nothing to contend for: both would find nothing, both would
 * insert, and neither would have excluded the other. A row that always exists is the thing to
 * contend for -- {@link JdbcRowLocks} is what takes it.
 */
public final class JdbcAgents {

  private static final String ENSURE =
      """
      INSERT INTO nessy_agent (agent_type, agent_id)
      VALUES (?, ?)
          ON CONFLICT (agent_type, agent_id) DO NOTHING
      """;

  private final JdbcClient jdbc;

  public JdbcAgents(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /** Brings this agent into being if it is new. Idempotent -- safe to call every time. */
  public void ensure(AgentType agentType, AgentId agent) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    jdbc.sql(ENSURE).params(agentType.value(), agent.value()).update();
  }
}
