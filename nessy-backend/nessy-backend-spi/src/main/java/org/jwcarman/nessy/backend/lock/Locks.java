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
package org.jwcarman.nessy.backend.lock;

import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * "Only one of us should do this right now, and I would rather wait than be told no."
 *
 * <p>A family of locks, one per {@code (kind, agent type, agent id)}, held by whoever asks first.
 * The key is the agent -- James has said twice that the lock is "around an agent type/agent id/kind
 * combo" -- so there is no opaque key to spell, and nothing to stringify: a caller that used to
 * write {@code agent.value().toString()} was silently sharing a namespace with every other kind of
 * work over that same agent id.
 *
 * <p><b>For a caller with nothing else to do but wait.</b> A harness asking to run a turn has
 * nobody else who could run it, and its caller is right there and would rather wait a short while
 * than be told no -- that is {@link #withLock}, the only verb here: it either runs the work or
 * throws, and never reports a refusal a caller has to branch on. Opportunistic work, where somebody
 * else running it instead is a fine outcome, belongs to {@link
 * org.jwcarman.nessy.backend.lease.Leases} instead, which is exactly why that family exists apart
 * from this one.
 *
 * <p><b>Exclusion here is exact, not believed.</b> What a kind is bound to and how it is stored
 * belongs to whatever is doing the excluding, but every implementation of this interface ties a
 * lock to something that cannot lie about being alive -- a database transaction, or a process that
 * releases everything it held the moment it exits -- so there is nothing approximate about who
 * holds one. That is also why a lock must stay short: nothing may hold one across anything slow,
 * such as a model call.
 */
public interface Locks {

  /**
   * A turn is running for this agent -- whichever door started it.
   *
   * <p>Shared rather than owned by either door: the direct door's {@code ask} and {@code terminate}
   * and the queued door's {@code tell}, {@code terminate} and {@code deliverOutcome} all lock under
   * this same kind, so a turn taken through one door excludes a turn taken through the other over
   * the same agent. A kind named after a door instead of the work would not do that.
   */
  LockKind TURN = new LockKind("nessy.agent.turn");

  /**
   * Runs {@code work} once the lock for {@code (kind, type, agent)} is held, waiting for it if it
   * must.
   *
   * <p><b>Waiting is the implementation's to do, and there is deliberately no default.</b> Every
   * substrate that belongs here can block properly -- a row lock has {@code FOR UPDATE}, which
   * queues waiters in the database; an in-memory one has a {@link java.util.concurrent.locks.Lock}
   * -- so there is no honest default that would not be a poll dressed up as a wait.
   */
  <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work);

  /** For a caller whose work produces nothing worth having, and would rather wait than be told. */
  default void withLock(LockKind kind, AgentType type, AgentId agent, Runnable work) {
    Objects.requireNonNull(work, "work must not be null");
    withLock(
        kind,
        type,
        agent,
        () -> {
          work.run();
          return null;
        });
  }
}
