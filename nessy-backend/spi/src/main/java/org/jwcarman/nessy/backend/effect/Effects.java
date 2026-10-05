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
package org.jwcarman.nessy.backend.effect;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * The effect table, row by row: what an agent owes the outside world.
 *
 * <p><b>The contract is a work queue.</b> {@link #markRunning} claims up to a batch size of due
 * rows of one agent type, oldest first, that nobody else currently holds; each claim counts the
 * attempt and sets the row's next due time to the earlier of two things -- when this attempt stops
 * being believed, and when the effect's own deadline gives up on it. That is the whole of the
 * contract. {@code SKIP LOCKED}, {@code LEAST} and {@code RETURNING} are how one implementation
 * happens to satisfy it, not what the contract says.
 *
 * <p><b>Correctness rests on per-statement atomicity plus the fold's idempotence, not on a
 * transaction spanning claim and completion.</b> {@link #complete} and {@link #reschedule} are a
 * compare-and-act fence on a row's status and attempt count. A lost fence is harmless: the row
 * simply comes due again, is performed again, and the fold ignores the redelivery.
 *
 * <p><b>{@link #insert} is the exception.</b> It is called from inside the fold's own transaction,
 * so the effect commits with the state change that owed it, or not at all. An implementation with
 * no transaction of its own -- an in-memory one, say -- does not provide that guarantee.
 */
public interface Effects {

  /**
   * Writes down an effect to be performed later, and beside it the outcome to deliver if it never
   * can be.
   */
  void insert(
      AgentType type,
      AgentId agent,
      AgentEffect effect,
      Duration timeout,
      EffectOutcome undispatchable,
      Instant deadline,
      String traceContext,
      Instant at);

  /** Claims up to {@code batchSize} due rows of one agent type, oldest first. */
  List<Attempt> markRunning(AgentType type, Instant now, int batchSize);

  /** Every claimed, unfinished row of one agent -- the candidates a late answer could name. */
  List<Attempt> runningFor(AgentType type, AgentId agent);

  /** Retires a finished attempt. Fenced on the attempt's own status and count. */
  boolean complete(UUID effectId, int attemptsMade);

  /**
   * Puts a failed attempt back for another go, fenced the same way.
   *
   * @param failedAttempts what every attempt so far has learned, this one included. The caller
   *     reads what was there, appends, and hands back the lot -- a stored list cannot be appended
   *     to in a statement. Empty for work that learned nothing worth keeping: a tool call knows
   *     only that it failed, with no classification and no count.
   */
  boolean reschedule(
      UUID effectId, int attemptsMade, Instant at, List<FailedAttempt> failedAttempts);

  /**
   * Marks a running attempt as parked at {@code at} and makes its row due at its deadline, so
   * nothing takes it again before then. Fenced on the attempt's own status and count.
   *
   * @return false when no such row is there: it was settled, or another attempt holds it
   */
  boolean park(UUID effectId, int attemptsMade, Instant at);

  /**
   * What the attempts before this one learned, read back off the row.
   *
   * <p>Here rather than on {@link Attempt} for the same reason {@link #effectOf} is: the row keeps
   * bytes, and only an implementation holding the codec that wrote them can say what they mean.
   */
  List<FailedAttempt> attemptsOf(Attempt attempt);

  /** Reads the effect an attempt is for. */
  AgentEffect effectOf(Attempt attempt);

  /** Reads the outcome to deliver when {@link #effectOf} cannot be read. */
  EffectOutcome failureOf(Attempt attempt);
}
