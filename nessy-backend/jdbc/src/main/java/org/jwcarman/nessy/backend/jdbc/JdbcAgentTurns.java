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
package org.jwcarman.nessy.backend.jdbc;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link AgentTurns} over rows in {@code nessy_agent_turn}. */
public final class JdbcAgentTurns implements AgentTurns {

  private final JdbcClient jdbc;

  public JdbcAgentTurns(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void record(AgentType type, AgentId agent, AgentTurn turn) {
    throw new UnsupportedOperationException("written in the next task");
  }

  @Override
  public List<AgentTurn> of(AgentType type, AgentId agent) {
    throw new UnsupportedOperationException("written in the next task");
  }
}
