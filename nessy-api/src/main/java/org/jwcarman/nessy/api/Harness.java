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
   * Says an application is finished with this agent instance, so everything it holds can go.
   *
   * <p>An agent id is not always a long-lived name. A browser session, one review by a judging
   * agent, a single request — those instances have to be able to END, or every one of them is a
   * permanent state row and a permanent transcript.
   *
   * <p><b>A request, not a receipt.</b> This returns as soon as the agent has been told. If it is
   * mid-turn it finishes first and then forgets itself, so nothing is deleted out from under work
   * in flight — the same cooperation {@code Thread.interrupt} asks for, and for the same reason:
   * the alternative strands an answer in a dead incarnation. A caller that must KNOW the agent is
   * gone cannot learn it here.
   *
   * <p>Forgetting an agent that never existed is silent. Telling one twice is the same as once.
   *
   * <p>What goes: the agent's memory, its backlog rows, its claims, and its persisted state. What
   * stays: stores it merely used — a notebook, a plan, a declared intent — because those have their
   * own lifecycles and may be shared with other agents. Forget those yourself if you want them
   * gone.
   *
   * <p><b>Reusing a forgotten id is asking for trouble.</b> An observation offered between the
   * decision to forget and the deletion lands in a table nobody is reading; it is harmless until
   * that id comes back, when it would arrive as stale work for a new agent.
   */
  void forget(AgentId agentId);

  /**
   * Listens to one agent's turns from here on. Close the returned subscription to stop; dropping it
   * unclosed leaks a routing entry, never a thread.
   *
   * @param agentId which agent
   * @param subscriber who is watching
   * @return a handle for stopping the subscription
   */
  AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber);

  /**
   * Listens from where a previous listener left off.
   *
   * <p>{@code lastEventId} is the {@link AgentEvent#id()} of the last event that listener actually
   * received. Everything narrated after it is replayed before live events resume, so a subscriber
   * that dropped and came back does not have a hole where the events it missed should be.
   *
   * <p>This is the shape a browser hands you: SSE reconnects carry a {@code Last-Event-ID} header
   * holding exactly this, so a controller can pass it straight through. Event ids are UUIDv7 and
   * therefore time-ordered, which is what lets one double as a cursor.
   *
   * <p>Replay is best-effort and bounded: an agent keeps a limited window of recent events, so a
   * listener gone long enough gets whatever is still there rather than everything it missed. A null
   * {@code lastEventId} means "start from now" and is what {@link #subscribe(AgentId,
   * AgentSubscriber)} passes.
   */
  AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber, String lastEventId);
}
