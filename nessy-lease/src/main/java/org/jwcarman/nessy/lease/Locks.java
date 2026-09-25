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
package org.jwcarman.nessy.lease;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * "Only one of us should do this right now."
 *
 * <p>A family of locks, one per key, for a single kind of work -- the summarising of an agent, say.
 * Whoever asks first runs; anybody else asking meanwhile is told no and does nothing. There is no
 * waiting and no queue.
 *
 * <p><b>Being refused has to be an acceptable answer</b>, which it is in two different ways.
 * Summarising an agent is opportunistic: somebody will do it eventually, it does not matter who,
 * and it matters that it is not two of us at once. A harness asking to run a turn is the other way
 * -- nothing else will pick it up, but its caller is right there and is handed the refusal, so it
 * can say the scope is busy rather than quietly running a second turn over the first. What this
 * cannot do is promise work gets done when nobody is told it did not.
 *
 * <p><b>What the kind is, and how long a holder may hold, are not here.</b> They belong to whatever
 * is doing the excluding: a lease has a kind and a time-to-live because a holder on another machine
 * can die without releasing, and a lock inside one process has neither because the process exiting
 * releases everything. A caller states what it wants -- at most one of us, now -- and is handed
 * something that knows how to arrange it.
 *
 * <p><b>The work must be idempotent, or at least harmless to redo.</b> This holds whichever
 * implementation a caller was given, and it is not the same promise as the exclusion itself: where
 * a holder can die, a holder that is merely slow is indistinguishable from one that died, so its
 * work can be taken over while it is still running. A caller cannot tell which implementation it
 * has, so it must assume the weaker one.
 */
public interface Locks {

  /**
   * Runs {@code work} if the lock for {@code key} can be taken, and says what came of it.
   *
   * <p>The lock is released when the work returns, however it returns; an exception from the work
   * is the caller's, after the release.
   *
   * <p><b>Re-entering is undefined.</b> Work that asks for a lock from this same instance may be
   * let through, refused or wedged depending on what is underneath, so do not.
   *
   * @return what the work produced, or {@link Attempt.Ignored} if somebody else held the lock
   */
  <T> Attempt<T> tryWithLock(String key, Supplier<T> work);

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
     * <p>Deliberately says nothing about who holds it or for how long. A striped local lock knows
     * neither, and a lease knows only what a row said a moment ago.
     */
    record Ignored<T>() implements Attempt<T> {}

    /** What the work produced, or {@code other} if it never ran. */
    default T orElse(T other) {
      return this instanceof Ran<T>(T result) ? result : other;
    }
  }

  /** For a caller whose work produces nothing worth having. */
  default Attempt<Void> tryWithLock(String key, Runnable work) {
    Objects.requireNonNull(work, "work must not be null");
    return tryWithLock(
        key,
        () -> {
          work.run();
          return null;
        });
  }
}
