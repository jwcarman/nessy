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

import java.time.Duration;

/**
 * "Only one of us should do this right now."
 *
 * <p>A lease is held for a {@code (kind, key)} pair -- {@code ("summary", agentId)}, say -- by
 * whoever asks first, for at most the time they asked for. Anybody else asking meanwhile is told no
 * and does nothing: there is no waiting and no queue, because the work a lease guards is
 * opportunistic. Somebody will summarise this agent eventually; it does not matter who, and it
 * matters that it is not two of us at once. Work somebody is owed is not this: that wants a
 * guarantee, and a lease offers none.
 *
 * <p>A holder that finishes releases the lease. A holder that dies leaves it to expire, after which
 * the next caller takes it over -- which is also why the work should be idempotent, or at least
 * harmless to redo: a holder that is merely slow is indistinguishable from one that died.
 */
public interface Leases {

  /**
   * Runs {@code work} if the lease for {@code (kind, key)} can be taken, and says whether it did.
   *
   * <p>The lease is released when the work returns, however it returns; an exception from the work
   * is the caller's, after the release.
   *
   * @param ttl how long the work may hold the lease before another caller may assume it died
   */
  boolean tryRun(String kind, String key, Duration ttl, Runnable work);
}
