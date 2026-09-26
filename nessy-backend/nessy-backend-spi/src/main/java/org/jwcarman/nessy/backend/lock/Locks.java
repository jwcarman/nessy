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

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * "Only one of us should do this right now."
 *
 * <p>A family of locks, one per {@code (kind, agent type, agent id)}, held by whoever asks first.
 * The key is the agent -- James has said twice that the lock is "around an agent type/agent id/kind
 * combo" -- so there is no opaque key to spell, and nothing to stringify: a caller that used to
 * write {@code agent.value().toString()} was silently sharing a namespace with every other kind of
 * work over that same agent id.
 *
 * <p><b>Two verbs, for two different callers.</b> Summarising an agent is opportunistic: somebody
 * will do it eventually, it does not matter who, and it matters that it is not two of us at once --
 * that caller wants {@link #tryWithLock}, which never waits and is refused at once if somebody else
 * holds it. A harness asking to run a turn is the other way -- nothing else will pick it up, and
 * its caller is right there and would rather wait a short while than be told no -- so that caller
 * wants {@link #withLock}, which asks until it gets the lock.
 *
 * <p><b>What the kind is bound to, and how long a holder may hold, are not here.</b> The kind is a
 * parameter of every call, but what a kind means -- how it is stored, whether a holder can die
 * without releasing -- belongs to whatever is doing the excluding: a lease has a time-to-live
 * because a holder on another machine can die without releasing, and a lock inside one process has
 * none because the process exiting releases everything. A caller states what it wants -- this kind
 * of work, over this agent, at most one of us, now or eventually -- and is handed something that
 * knows how to arrange it.
 *
 * <p><b>The work must be idempotent, or at least harmless to redo.</b> This holds whichever
 * implementation a caller was given, and it is not the same promise as the exclusion itself: where
 * a holder can die, a holder that is merely slow is indistinguishable from one that died, so its
 * work can be taken over while it is still running. A caller cannot tell which implementation it
 * has, so it must assume the weaker one.
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
   * How often the default {@link #withLock} asks again after being refused.
   *
   * <p>Short enough that a caller waiting for a step -- a database round trip, not an inference --
   * is not made to feel it, long enough that polling is not itself a source of load. An
   * implementation that can wait natively, such as a row lock's {@code FOR UPDATE}, overrides this
   * default rather than living with the poll.
   */
  Duration POLL_INTERVAL = Duration.ofMillis(20);

  /**
   * Runs {@code work} if the lock for {@code (kind, type, agent)} can be taken, and says what came
   * of it.
   *
   * <p>The lock is released when the work returns, however it returns; an exception from the work
   * is the caller's, after the release.
   *
   * <p><b>Re-entering is undefined.</b> Work that asks for a lock from this same instance may be
   * let through, refused or wedged depending on what is underneath, so do not.
   *
   * @return what the work produced, or {@link Attempt.Ignored} if somebody else held the lock
   */
  <T> Attempt<T> tryWithLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work);

  /**
   * Runs {@code work} once the lock for {@code (kind, type, agent)} is held, waiting for it if it
   * must.
   *
   * <p>The default polls {@link #tryWithLock} every {@link #POLL_INTERVAL} until it succeeds, which
   * lets any {@link Locks} satisfy this signature honestly, a lease included: slow, unfair -- a
   * late arrival can win a poll a longer-waiting caller loses -- and a round trip per attempt. An
   * implementation that can wait natively, such as a database row lock, overrides this with
   * something that actually blocks and queues waiters in arrival order.
   */
  default <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work) {
    while (true) {
      if (tryWithLock(kind, type, agent, work) instanceof Attempt.Ran<T>(T result)) {
        return result;
      }
      try {
        Thread.sleep(POLL_INTERVAL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for " + kind, e);
      }
    }
  }

  /** For a caller whose work produces nothing worth having. */
  default Attempt<Void> tryWithLock(LockKind kind, AgentType type, AgentId agent, Runnable work) {
    Objects.requireNonNull(work, "work must not be null");
    return tryWithLock(
        kind,
        type,
        agent,
        () -> {
          work.run();
          return null;
        });
  }

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

  /**
   * Whether the work ran, and what it produced.
   *
   * <p>Two arms rather than an empty optional, because "nobody ran it" and "it ran and produced
   * nothing" are different answers and a caller acts differently on each. An optional would report
   * side-effecting work -- the kind this most often guards -- as though it had been refused.
   */
  sealed interface Attempt<T> {

    /** It ran, and this is what it returned -- which may be null, if that is what the work says. */
    record Ran<T>(T result) implements Attempt<T> {}

    /**
     * Somebody else held the lock, so nothing happened.
     *
     * <p>Deliberately says nothing about who holds it or for how long. A lock inside one process
     * knows neither, and a lease knows only what a row said a moment ago.
     */
    record Ignored<T>() implements Attempt<T> {}

    /** What the work produced, or {@code other} if it never ran. */
    default T orElse(T other) {
      return this instanceof Ran<T>(T result) ? result : other;
    }
  }
}
