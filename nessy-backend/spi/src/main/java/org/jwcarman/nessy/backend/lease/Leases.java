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
package org.jwcarman.nessy.backend.lease;

import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * "Somebody will do this eventually; it does not matter who, it matters that it is not two of us at
 * once."
 *
 * <p>A family of leases, one per {@code (kind, agent type, agent id)}, taken by whoever asks first
 * and believed to be held for only as long as its holder said it would need. That belief is the
 * whole of the guarantee: a lease bounds how long a holder is BELIEVED to be working, not how long
 * it actually runs, so a holder that is merely slow is indistinguishable from one that died and its
 * work can be taken over while it is still running. Whatever a lease guards must therefore be
 * idempotent, or at least harmless to redo.
 *
 * <p><b>There is deliberately no waiting verb.</b> Waiting on a lease would mean polling, since
 * nothing about a lease can be blocked on the way a database row lock can, and the only caller a
 * lease is for does not want that: it is opportunistic work where a refusal costs nothing but a
 * short delay, and the next occasion tries again. A caller refused a lease skips the work rather
 * than waiting for it.
 *
 * <p><b>Contrast with {@link org.jwcarman.nessy.backend.lock.Locks}.</b> A lock is exact -- a
 * caller either gets it or is told to wait, and while held it excludes for as long as it is held,
 * with nothing approximate about who holds it. That exactness is bought by tying the lock to
 * something that cannot lie about being alive, such as a database transaction, which is also why a
 * lock must stay short: nothing may be held open across something slow. Reach for a lock when a
 * short critical section must be atomic with its own writes; reach for a lease when the work to
 * guard is slow -- a model call, say -- and nobody must be allowed to do it twice at once, but it
 * is fine, even expected, that a slow or crashed holder eventually gets taken over.
 */
public interface Leases {

  /**
   * Runs {@code work} if the lease for {@code (kind, type, agent)} can be taken, and says what came
   * of it.
   *
   * <p>The lease is released when the work returns, however it returns; an exception from the work
   * is the caller's, after the release. Never waits: a lease already believed held by somebody else
   * is an immediate refusal, not a delay.
   *
   * <p><b>Re-entering is undefined.</b> Work that asks for a lease from this same instance may be
   * let through, refused or wedged depending on what is underneath, so do not.
   *
   * @return what the work produced, or {@link Attempt.Ignored} if somebody else held the lease
   */
  <T> Attempt<T> tryWithLease(LeaseKind kind, AgentType type, AgentId agent, Supplier<T> work);

  /** For a caller whose work produces nothing worth having. */
  default Attempt<Void> tryWithLease(LeaseKind kind, AgentType type, AgentId agent, Runnable work) {
    Objects.requireNonNull(work, "work must not be null");
    return tryWithLease(
        kind,
        type,
        agent,
        () -> {
          work.run();
          return null;
        });
  }
}
