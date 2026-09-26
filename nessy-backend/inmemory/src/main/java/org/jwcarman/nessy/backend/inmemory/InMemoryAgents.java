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

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntBiFunction;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.agent.Agents;

/**
 * Which agents exist and which have ended, held in this process and nowhere else.
 *
 * <p>The durable one is a table with a {@code terminated_at} column. This is a set. Neither keeps
 * anything else about an agent, because there is nothing else to keep: an agent IS its stream of
 * events, and this answers the two questions that cannot be asked of a stream -- has it been told
 * to stop, and stop it.
 *
 * <p><b>Sealing clears the backlog, and that is not this type reaching past its own concern.</b>
 * The durable one does the same, in the same statement pair: mark the agent, then delete what was
 * waiting for it. Ending an agent means nothing is owed to it any more, and leaving inputs queued
 * for something that will never run them would be a slow leak of work nobody will do. The backlog
 * it clears is handed in rather than looked up, so this stays a thing that knows about agents and
 * not a thing that knows about queues.
 */
public final class InMemoryAgents implements Agents {

  private record Key(AgentType type, AgentId agent) {}

  private final Set<Key> known = ConcurrentHashMap.newKeySet();
  private final Set<Key> ended = ConcurrentHashMap.newKeySet();
  private final ToIntBiFunction<AgentType, AgentId> clearBacklog;

  /** For a door with no backlog to clear, which is every direct one. */
  public InMemoryAgents() {
    this((type, agent) -> 0);
  }

  /**
   * @param clearBacklog abandons whatever is waiting for one agent, and says how much there was.
   */
  public InMemoryAgents(ToIntBiFunction<AgentType, AgentId> clearBacklog) {
    this.clearBacklog = Objects.requireNonNull(clearBacklog, "clearBacklog must not be null");
  }

  @Override
  public void ensure(AgentType type, AgentId agent) {
    known.add(key(type, agent));
  }

  @Override
  public boolean terminated(AgentType type, AgentId agent) {
    return ended.contains(key(type, agent));
  }

  /**
   * Idempotent, as the durable one is: its {@code COALESCE(terminated_at, now())} keeps the first
   * ending rather than moving it, and sealing twice here abandons nothing the second time because
   * the first already emptied the backlog.
   */
  @Override
  public int seal(AgentType type, AgentId agent) {
    Key key = key(type, agent);
    known.add(key);
    ended.add(key);
    return clearBacklog.applyAsInt(type, agent);
  }

  private static Key key(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return new Key(type, agent);
  }
}
