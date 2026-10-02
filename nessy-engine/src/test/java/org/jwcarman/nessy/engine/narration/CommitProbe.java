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
package org.jwcarman.nessy.engine.narration;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;

/**
 * Locks that misbehave on the way out, in the one place the commit of a step happens: after the
 * work has run and before {@code withLock} hands back.
 *
 * <p>Calls are numbered from one in the order they begin, so a test names the step it means. A
 * failed commit is simulated by throwing once the work is done, which is what a failed commit looks
 * like to the caller; a held return stops the calling thread after the commit and before it goes
 * on.
 */
public final class CommitProbe implements Locks {

  private static final long PATIENCE_SECONDS = 20;

  private final Locks delegate;
  private final AtomicInteger calls = new AtomicInteger();
  private final Set<Integer> failing = ConcurrentHashMap.newKeySet();
  private final Map<Integer, Hold> holds = new ConcurrentHashMap<>();

  public CommitProbe(Locks delegate) {
    this.delegate = delegate;
  }

  /** The commit of call {@code number} fails: the work runs, then {@code withLock} throws. */
  public CommitProbe failTheCommitOfCall(int number) {
    failing.add(number);
    return this;
  }

  /** The thread making call {@code number} stops after its commit, until the hold is released. */
  public Hold holdTheReturnOfCall(int number) {
    Hold hold = new Hold();
    holds.put(number, hold);
    return hold;
  }

  @Override
  public <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work) {
    int call = calls.incrementAndGet();
    T result = delegate.withLock(kind, type, agent, work);
    if (failing.contains(call)) {
      throw new IllegalStateException("the commit of call " + call + " failed");
    }
    Hold hold = holds.get(call);
    if (hold != null) {
      hold.arrive();
    }
    return result;
  }

  /** A thread held after a commit, and the means to let it go. */
  public static final class Hold {

    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);

    private void arrive() {
      reached.countDown();
      try {
        if (!proceed.await(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("a held commit was never let go");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while held", interrupted);
      }
    }

    /** Blocks until the held thread has committed and stopped. */
    public void awaitReached() {
      try {
        if (!reached.await(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("the held call was never made");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting", interrupted);
      }
    }

    public void release() {
      proceed.countDown();
    }
  }
}
