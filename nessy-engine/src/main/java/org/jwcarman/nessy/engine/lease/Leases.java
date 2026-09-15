package org.jwcarman.nessy.engine.lease;

import java.time.Duration;

/**
 * "Only one of us should do this right now."
 *
 * <p>A lease is held for a {@code (kind, key)} pair -- {@code ("summary", agentId)}, say -- by
 * whoever asks first, for at most the time they asked for. Anybody else asking meanwhile is told no
 * and does nothing: there is no waiting and no queue, because the work a lease guards is
 * opportunistic. Somebody will summarise this agent eventually; it does not matter who, and it
 * matters that it is not two of us at once. Work an agent is owed is not this; that is an effect,
 * and effects are guaranteed.
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
