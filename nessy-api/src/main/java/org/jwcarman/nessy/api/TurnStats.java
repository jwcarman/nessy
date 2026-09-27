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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What a turn has done so far.
 *
 * <p>Kept in the agent's state and moved by the fold, so it is rebuilt by replay like everything
 * else: a turn resumed after a restart does not forget that it already spent six calls.
 *
 * <p><b>Facts only. Elapsed is not one of them.</b> The fold is replayed, so a stored duration
 * would be measured against whatever clock happened to be running at replay time and the fold would
 * stop being deterministic. {@link #startedAt()} is a fact; {@link #elapsed(Instant)} works the
 * duration out when somebody asks.
 *
 * <p><b>The counts do not partition, on purpose.</b> {@code modelCalls} is every call the turn made
 * and {@code spent} is everything it cost, because those are what a reader means by the words -- a
 * turn reporting two calls when the engine made five would be wrong exactly where it matters,
 * reconciling against a bill or a rate limit. {@code failedAttempts} and {@code wasted} are the
 * subsets that bought nothing. Subtract to get the rest, through {@link #productiveCalls()} and
 * {@link #productiveTokens()} rather than by hand, so that everyone reading gets the same answer.
 *
 * <p><b>Why the waste is worth its own number.</b> Total spend is a budget signal; the share of it
 * that bought nothing is a thrashing signal, and thrashing is the case actually worth ending a turn
 * over. A turn spending steadily is working. A turn spending on failures is stuck. With only a
 * total the two are indistinguishable.
 *
 * <p>Spend here is input plus output, which is what is billed -- cache reads are already inside the
 * input count by the normalisation {@link Usage} documents. A turn's cache and reasoning breakdown
 * lives per call on the events, where the detail is still attributable to one model.
 */
public record TurnStats(
    Instant startedAt,
    int modelCalls,
    int toolCalls,
    int failedAttempts,
    Tokens spent,
    Tokens wasted) {

  public TurnStats {
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    Objects.requireNonNull(spent, "spent must not be null");
    Objects.requireNonNull(wasted, "wasted must not be null");
  }

  /** A turn that has just opened and done nothing yet. */
  public static TurnStats opened(Instant at) {
    return new TurnStats(at, 0, 0, 0, Tokens.none(), Tokens.none());
  }

  /** A call came back with an answer or a refusal: one call, and what it cost. */
  public TurnStats answered(Usage usage) {
    return counting(usage, 0, 0);
  }

  /** A call came back asking for work: one call, what it cost, and the calls it asked for. */
  public TurnStats requestedActions(int actions, Usage usage) {
    return counting(usage, actions, 0);
  }

  /**
   * A call produced nothing: one call, what it cost, and one attempt that bought nothing.
   *
   * <p>Both a retried attempt and the failure that ends a turn come here. The call is counted
   * because it was made -- a request crossed the wire and a rate limit was consumed by it -- and
   * counted again as wasted because nothing came back.
   */
  public TurnStats failed(Usage usage) {
    return counting(usage, 0, 1);
  }

  private TurnStats counting(Usage usage, int actions, int failures) {
    Tokens cost = usage.totalTokens();
    return new TurnStats(
        startedAt,
        modelCalls + 1,
        toolCalls + actions,
        failedAttempts + failures,
        spent.plus(cost),
        failures == 0 ? wasted : wasted.plus(cost));
  }

  /** How long this turn has been open, as of now. */
  public Duration elapsed(Instant now) {
    return Duration.between(startedAt, Objects.requireNonNull(now, "now must not be null"));
  }

  /** The calls that got the turn somewhere. */
  public int productiveCalls() {
    return modelCalls - failedAttempts;
  }

  /** What the spending bought, which is everything it cost less what it wasted. */
  public Tokens productiveTokens() {
    return spent.minus(wasted);
  }
}
