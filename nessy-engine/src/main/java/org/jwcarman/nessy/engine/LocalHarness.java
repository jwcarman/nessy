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
package org.jwcarman.nessy.engine;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentSubscription;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.engine.agent.Input;

/**
 * The application's whole surface onto one kind of agent.
 *
 * <p><b>No registry, and nothing to go stale.</b> The sharded version said the same thing for a
 * different reason -- sharding owned the map from id to address. Here there is no address at all:
 * an agent id names a row, and any node can take it.
 *
 * @param <O> the observation type
 */
final class LocalHarness<O> implements Harness<O> {

  private static final String AGENT_ID_NOT_NULL = "agentId must not be null";

  private final AgentType type;
  private final BacklogStore<O> backlog;
  private final Dispatcher dispatcher;
  private final Narration narration;

  LocalHarness(
      AgentType type, BacklogStore<O> backlog, Dispatcher dispatcher, Narration narration) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.backlog = Objects.requireNonNull(backlog, "backlog must not be null");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
    this.narration = Objects.requireNonNull(narration, "narration must not be null");
  }

  @Override
  public AgentType type() {
    return type;
  }

  @Override
  public void observe(AgentId agentId, O observation) {
    Objects.requireNonNull(agentId, AGENT_ID_NOT_NULL);
    Objects.requireNonNull(observation, "observation must not be null");
    // COMMIT, then signal. Reversed, the agent could take before the row lands, find nothing, and
    // go back to sleep with work sitting in the table.
    backlog.offer(agentId, observation);
    dispatcher.dispatch(agentId, new Input.BacklogUpdated());
  }

  /**
   * Leaves a forget where the agent will find it, and nudges.
   *
   * <p>A row rather than a message, for the reason it always was: a delete ordered against nothing
   * can overtake the writes of the turn it followed. The agent learns it is doomed by TAKING the
   * poison, which is provably after the batch that asked for it has finished.
   */
  @Override
  public void forget(AgentId agentId) {
    Objects.requireNonNull(agentId, AGENT_ID_NOT_NULL);
    backlog.poison(agentId);
    narration.forget(agentId);
    dispatcher.dispatch(agentId, new Input.BacklogUpdated());
  }

  @Override
  public AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber) {
    return narration.subscribe(agentId, subscriber);
  }

  /**
   * Replays from {@link Narration}'s own bounded, in-memory recent-events buffer -- the same
   * bargain {@code NarrationActor} struck, and no bigger a promise: a cursor from BEFORE this
   * process started, or from further back than the buffer holds, replays only what survived.
   * Durable, cross-restart replay is Phase 3's Substrate journal, not this.
   */
  @Override
  public AgentSubscription subscribe(
      AgentId agentId, AgentSubscriber subscriber, String lastEventId) {
    Objects.requireNonNull(agentId, AGENT_ID_NOT_NULL);
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    return narration.subscribe(agentId, subscriber, lastEventId);
  }
}
