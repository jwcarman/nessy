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
package org.jwcarman.nessy.engine.inmemory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;

/**
 * Locks held in this process and nowhere else.
 *
 * <p>For a CLI, a test, or anything else with one JVM and no database. There is no time-to-live
 * because it means nothing here: a holder cannot die without releasing, since the process exiting
 * releases everything it held.
 *
 * <p><b>One lock per {@code (kind, type, agent)}, not striped.</b> A map that grows for as long as
 * the process runs and is pruned when the last holder leaves needs care to avoid a race between
 * "nobody holds this key" and "somebody is about to", which is exactly what {@link
 * ConcurrentHashMap}'s atomic {@code compute}/{@code computeIfPresent} give for free: each runs its
 * function while holding that key's internal bin, so a lookup, an insert and a removal over the
 * same key can never interleave.
 *
 * <p><b>Entries are removed once nobody holds them.</b> A key stops mattering the moment its last
 * holder releases -- there is nothing left to protect -- and a map that only ever grew would be a
 * leak in exactly the long-running process this class exists for (a CLI held open, a test suite). A
 * held entry counts its holders so a second waiter racing the first's release does not delete the
 * lock out from under it.
 */
public final class InMemoryLocks implements Locks {

  /** One lock per key, plus how many callers are relying on it still being in the map. */
  private static final class Entry {
    private final Lock lock = new ReentrantLock();
    private int holders;
  }

  private final ConcurrentHashMap<Key, Entry> locks = new ConcurrentHashMap<>();

  private record Key(LockKind kind, AgentType type, AgentId agent) {}

  /**
   * Blocks on the entry's own lock rather than asking for it over and over.
   *
   * <p>The claim is taken first, so the entry cannot be evicted by a releasing holder while this
   * caller is still waiting on it -- {@code holders} is what keeps it in the map, and a waiter is a
   * holder of the entry even before it holds the lock.
   */
  @Override
  public <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(work, "work must not be null");
    Key key = new Key(kind, type, agent);
    Entry entry = locks.compute(key, (_, existing) -> claim(existing));
    // lock rather than tryLock: waiting is the contract here, and ReentrantLock queues waiters
    // instead of leaving them to race.
    entry.lock.lock();
    try {
      return work.get();
    } finally {
      entry.lock.unlock();
      release(key);
    }
  }

  private static Entry claim(Entry existing) {
    Entry entry = existing == null ? new Entry() : existing;
    entry.holders++;
    return entry;
  }

  /** Drops this caller's claim, removing the entry once nobody else is still relying on it. */
  private void release(Key key) {
    locks.computeIfPresent(
        key,
        (_, entry) -> {
          entry.holders--;
          return entry.holders == 0 ? null : entry;
        });
  }
}
