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

import org.jwcarman.codec.TypeRef;

/**
 * Runs a turn on the calling thread and hands back what it came to.
 *
 * <p>The door for work somebody is waiting on, and the opposite bargain to a {@link QueuedHarness}:
 * that one always accepts and says nothing, this one answers and may decline to start. Neither is a
 * special case of the other. Everything a queued harness does because nobody is waiting --
 * queueing, coalescing, deferral, resumption, one-turn-at-a-time -- is absent here, not because it
 * is forbidden but because nothing in this world can produce it: the caller is the only thing that
 * can start a turn, and it is standing right there holding the answer.
 *
 * <p><b>Whether it remembers is the store's business.</b> The same harness backed by memory forgets
 * and backed by a durable store resumes; neither changes a line of this interface.
 *
 * <p><b>One turn at a time per agent, and the second caller is told so.</b> A queued harness gets
 * that from its queue; this gets it from a lock, and being refused is an {@link Outcome.Busy}
 * rather than a wait -- the caller is standing right there and would rather know than block. What
 * kind of lock decides whether that holds across machines or only inside this one.
 *
 * <p><b>TODO -- James:</b> the name.
 */
public interface DirectHarness<I> {

  /**
   * One turn, start to finish, before this returns.
   *
   * <p>Answers rather than throws for anything it understands: a model declining, a turn running
   * out of budget, a provider being unreachable and the agent already being busy are outcomes to
   * branch on, not faults.
   */
  Outcome<String> ask(AgentId agent, I input);

  /**
   * One turn, answered in the shape of {@code type}.
   *
   * <p>The schema goes to the provider, which constrains the answer on its own wire -- natively on
   * every vendor that has it -- and what comes back is parsed into {@code type} before the caller
   * sees it. A caller never learns which mechanism the vendor used, which is the point.
   *
   * <p>Support is not universal and is sometimes per model: a vendor that will not constrain an
   * answer, or a model that answers around the shape, ends the turn {@link Outcome.Failed} rather
   * than handing back something that does not fit.
   */
  <T> Outcome<T> ask(AgentId agent, I input, TypeRef<T> type);

  /** The common case: a shape with no type arguments to capture. */
  default <T> Outcome<T> ask(AgentId agent, I input, Class<T> type) {
    return ask(agent, input, TypeRef.of(type));
  }

  /**
   * This agent is finished.
   *
   * <p>It refuses everything afterwards, loudly, and there is no way back -- so this is a decision
   * about the agent rather than about this object. Takes the same lock a turn does, so ending an
   * agent mid-turn does nothing; ask again once the turn has ended. Letting a harness be collected
   * leaves its agent resumable, which is the right default: abandoning a conversation and ending
   * one are different acts, and only one of them has a method.
   */
  void terminate(AgentId agent);
}
