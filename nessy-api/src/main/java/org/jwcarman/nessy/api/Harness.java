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
package org.jwcarman.nessy.api;

/**
 * The application's whole surface onto a running set of agents of one kind.
 *
 * <p>Two ways to interact, and no more: tell an agent what happened, and listen to what it does.
 * There is no handle to an individual agent — that would only curry the id — and no door for
 * returning approvals or tool results, which are the engine's plumbing rather than application
 * vocabulary.
 *
 * <p>An agent is reached only by id, never held. It need not exist yet: telling an id something is
 * what brings that agent into being.
 *
 * @param <O> the observation type these agents accept
 */
public interface Harness<O> {

  /**
   * What kind of agent this harness serves. Every agent reached through it carries this type, and
   * an {@link AgentId} is only meaningful within it.
   *
   * <p>An accessor, not a third way to interact: a caller holding a harness can label a log line,
   * qualify a metric, or say which queue an approval belongs to without being handed the config
   * that built it.
   */
  AgentType type();

  /**
   * Something happened; tell the agent named {@code agentId}.
   *
   * @param agentId which agent
   * @param observation what happened
   */
  void observe(AgentId agentId, O observation);

  /**
   * Listens to one agent's turns from here on. Close the returned subscription to stop; dropping it
   * unclosed leaks a routing entry, never a thread.
   *
   * @param agentId which agent
   * @param subscriber who is watching
   * @return a handle for stopping the subscription
   */
  AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber);
}
