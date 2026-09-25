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
 * Runs a turn on the calling thread and hands back what it came to.
 *
 * <p>The door for work somebody is waiting on. Everything a {@link Harness} does because nobody is
 * waiting -- queueing, coalescing, deferral, resumption, one-turn-at-a-time -- is absent here, not
 * because it is forbidden but because nothing in this world can produce it: the caller is the only
 * thing that can start a turn, and it is standing right there holding the answer.
 *
 * <p><b>Whether it remembers is the store's business.</b> The same harness backed by memory forgets
 * and backed by a durable store resumes; neither changes a line of this interface.
 *
 * <p><b>It gives up serialization.</b> Two threads asking the same scope at once race on that
 * scope's stream, and nothing here stops them. A queued harness runs one turn at a time per agent;
 * this does not, and an application that needs it must arrange it.
 *
 * <p><b>TODO -- James:</b> the name, and {@link ScopeId}'s.
 */
public interface DirectHarness<I> {

  /**
   * One turn, start to finish, before this returns.
   *
   * <p>Answers rather than throws for anything it understands: a model declining, a turn running
   * out of budget and a provider being unreachable are outcomes to branch on, not faults.
   */
  Outcome ask(ScopeId scope, I input);

  /**
   * This agent is finished.
   *
   * <p>It refuses everything afterwards, loudly, and there is no way back -- so this is a decision
   * about the agent rather than about this object. Letting a harness be collected leaves its scope
   * resumable, which is the right default: abandoning a conversation and ending one are different
   * acts, and only one of them has a method.
   */
  void terminate(ScopeId scope);
}
