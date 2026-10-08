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
package org.jwcarman.nessy.backend.inmemory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;

/**
 * Completed turns held in this process and nowhere else. Known trajectories are kept for the life
 * of this instance and never forgotten, as a table nothing deletes from would keep them; nothing
 * rolls a sighting back.
 */
public final class InMemoryAgentTurns implements AgentTurns {

  private record Key(AgentType type, AgentId agent) {}

  private final Map<Key, List<AgentTurn>> turns = new ConcurrentHashMap<>();

  private record Known(AgentType type, String label, Trajectory trajectory) {}

  private final Set<Known> known = new HashSet<>();

  @Override
  public synchronized boolean firstSighting(
      AgentType type, String label, Trajectory trajectory, Instant at) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(label, "label must not be null");
    Objects.requireNonNull(trajectory, "trajectory must not be null");
    Objects.requireNonNull(at, "at must not be null");
    return known.add(new Known(type, label, trajectory));
  }

  @Override
  public synchronized void append(AgentType type, AgentId agent, AgentTurn turn) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(turn, "turn must not be null");
    List<AgentTurn> rows = turns.computeIfAbsent(new Key(type, agent), _ -> new ArrayList<>());
    if (rows.stream().anyMatch(row -> row.turn().equals(turn.turn()))) {
      throw new IllegalStateException(
          "agent " + agent.value() + " already has a row for turn " + turn.turn().value());
    }
    rows.add(turn);
  }

  @Override
  public synchronized List<AgentTurn> of(AgentType type, AgentId agent) {
    List<AgentTurn> rows = turns.get(new Key(type, agent));
    return rows == null ? List.of() : List.copyOf(rows);
  }
}
