package org.jwcarman.nessy.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * How hard to try an effect whose outcome was a failure, or was never observed at all.
 *
 * <p>A policy travels with the effect it governs rather than living in configuration, so a row
 * written yesterday keeps the rules it was created with -- editing a setting can neither revive an
 * exhausted effect nor condemn one already in flight.
 *
 * <p>It is asked about {@code attemptsMade}, which is a fact the row records, rather than about
 * attempts remaining, which is only a judgement about that fact. Storing the fact and deriving the
 * judgement is what lets a policy be something other than a count.
 *
 * <p>The wire names are short -- {@code none}, {@code fixed}, {@code exp} -- and are a stored
 * format rather than a Java name: every effect row carries one, and renaming a record must not
 * silently orphan rows already written. Changing one of these strings is a migration.
 *
 * <p>A policy decides only <em>how many</em> and <em>how long</em>. Whether a particular failure
 * deserves another attempt at all is classification, and that belongs to the adapter that caught it
 * -- only the model client knows what a 404 means. That separation is what keeps a policy plain
 * data: no exception classes, no predicates, nothing that could not be written to a row.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = RetryPolicy.Never.class, name = "none"),
  @JsonSubTypes.Type(value = RetryPolicy.FixedDelay.class, name = "fixed"),
  @JsonSubTypes.Type(value = RetryPolicy.Exponential.class, name = "exp")
})
public sealed interface RetryPolicy {

  /**
   * Whether the effect gets another attempt, and how long to wait first.
   *
   * <p>The generator is passed in rather than held. A policy owning one could not be serialized
   * into the row, and could not be tested -- given a seeded generator this method is a pure
   * function of its arguments, which is why every branch below is pinned by an assertion rather
   * than a tolerance.
   *
   * @param attemptsMade attempts started so far, including the one that just failed
   * @param random the source of jitter
   */
  RetryDecision decide(int attemptsMade, RandomGenerator random);

  /**
   * Spreads a wait by up to {@code jitter} in either direction, never below zero and never above
   * {@code cap}.
   *
   * <p>Jitter is what stops retries synchronising. A node that dies holding twenty effects makes
   * all twenty due at the same instant; without this they would retry in lockstep, fail together,
   * and back off together, arriving as a burst every time.
   */
  private static Duration spread(
      Duration wait, Duration jitter, Duration cap, RandomGenerator random) {
    long jitterMillis = jitter.toMillis();
    long spread = jitterMillis <= 0 ? 0 : random.nextLong(-jitterMillis, jitterMillis + 1);
    long millis = Math.max(0L, wait.toMillis() + spread);
    return Duration.ofMillis(Math.min(millis, cap.toMillis()));
  }

  /**
   * One attempt and no more. The right answer for anything that changes the world outside the
   * agent, where an unobserved outcome must be reconciled rather than repeated -- and the default
   * everywhere, so that forgetting to choose is safe.
   */
  record Never() implements RetryPolicy {

    @Override
    public RetryDecision decide(int attemptsMade, RandomGenerator random) {
      return new RetryDecision.GiveUp();
    }
  }

  /**
   * The same wait between every attempt, jittered, up to a limit.
   *
   * @param jitter how far either side of {@code delay} a wait may fall; zero for none
   */
  record FixedDelay(int maxAttempts, Duration delay, Duration jitter) implements RetryPolicy {

    public FixedDelay {
      maxAttempts = Math.max(1, maxAttempts);
      jitter = jitter == null ? Duration.ZERO : jitter;
    }

    @Override
    public RetryDecision decide(int attemptsMade, RandomGenerator random) {
      return attemptsMade >= maxAttempts
          ? new RetryDecision.GiveUp()
          : new RetryDecision.RetryAfter(spread(delay, jitter, delay.plus(jitter), random));
    }
  }

  /**
   * {@code delay * multiplier^(attemptsMade - 1)}, capped at {@code maxDelay}, then jittered.
   *
   * <p>The cap matters: without it a long budget produces a final wait measured in hours, and an
   * agent that recovers eventually is indistinguishable from one that never does. The cap is
   * applied before jitter and again after, so a jittered wait can never exceed it either.
   *
   * @param multiplier growth per attempt; 2.0 doubles, 1.0 degenerates to a fixed delay
   */
  record Exponential(
      int maxAttempts, Duration delay, double multiplier, Duration jitter, Duration maxDelay)
      implements RetryPolicy {

    public Exponential {
      maxAttempts = Math.max(1, maxAttempts);
      multiplier = Math.max(1.0, multiplier);
      jitter = jitter == null ? Duration.ZERO : jitter;
    }

    @Override
    public RetryDecision decide(int attemptsMade, RandomGenerator random) {
      if (attemptsMade >= maxAttempts) {
        return new RetryDecision.GiveUp();
      }
      double grown = delay.toMillis() * Math.pow(multiplier, Math.max(0, attemptsMade - 1));
      // Growth is computed in double and can overflow a long for a large budget, so the cap
      // is applied before the conversion rather than after.
      Duration wait = grown >= maxDelay.toMillis() ? maxDelay : Duration.ofMillis((long) grown);
      return new RetryDecision.RetryAfter(spread(wait, jitter, maxDelay, random));
    }
  }
}
