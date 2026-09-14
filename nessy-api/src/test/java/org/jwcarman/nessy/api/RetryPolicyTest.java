package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  /**
   * No jitter, so a wait is exactly what the curve says and can be asserted rather than bounded.
   */
  private static final RandomGenerator UNUSED = new Random(1);

  private static Duration backoffOf(RetryDecision decision) {
    assertThat(decision).isInstanceOf(RetryDecision.RetryAfter.class);
    return ((RetryDecision.RetryAfter) decision).backoff();
  }

  @Test
  void neverGivesUpOnTheFirstFailure() {
    assertThat(new RetryPolicy.Never().decide(1, UNUSED))
        .as("the default must not retry, so forgetting to choose is safe")
        .isInstanceOf(RetryDecision.GiveUp.class);
  }

  @Test
  void exponentialGrowsByItsMultiplier() {
    RetryPolicy policy =
        new RetryPolicy.Exponential(
            5, Duration.ofSeconds(2), 3.0, Duration.ZERO, Duration.ofHours(1));

    assertThat(backoffOf(policy.decide(1, UNUSED))).isEqualTo(Duration.ofSeconds(2));
    assertThat(backoffOf(policy.decide(2, UNUSED))).isEqualTo(Duration.ofSeconds(6));
    assertThat(backoffOf(policy.decide(3, UNUSED))).isEqualTo(Duration.ofSeconds(18));
  }

  @Test
  void aMultiplierOfOneIsJustAFixedDelay() {
    RetryPolicy policy =
        new RetryPolicy.Exponential(
            5, Duration.ofSeconds(2), 1.0, Duration.ZERO, Duration.ofHours(1));

    assertThat(backoffOf(policy.decide(4, UNUSED))).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void exponentialHonoursItsCap() {
    RetryPolicy policy =
        new RetryPolicy.Exponential(
            40, Duration.ofSeconds(2), 2.0, Duration.ZERO, Duration.ofSeconds(30));

    assertThat(backoffOf(policy.decide(30, UNUSED)))
        .as("doubling thirty times overflows a long; the cap must be applied first")
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void exponentialGivesUpOnceTheAttemptsAreSpent() {
    RetryPolicy policy =
        new RetryPolicy.Exponential(
            3, Duration.ofSeconds(2), 2.0, Duration.ZERO, Duration.ofMinutes(1));

    assertThat(policy.decide(3, UNUSED)).isInstanceOf(RetryDecision.GiveUp.class);
    assertThat(policy.decide(9, UNUSED))
        .as("an over-spent effect must still give up rather than retry")
        .isInstanceOf(RetryDecision.GiveUp.class);
  }

  @Test
  void fixedDelayWaitsTheSameEachTimeUntilItStops() {
    RetryPolicy policy = new RetryPolicy.FixedDelay(3, Duration.ofSeconds(5), Duration.ZERO);

    assertThat(backoffOf(policy.decide(1, UNUSED))).isEqualTo(Duration.ofSeconds(5));
    assertThat(backoffOf(policy.decide(2, UNUSED))).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.decide(3, UNUSED)).isInstanceOf(RetryDecision.GiveUp.class);
  }

  @Test
  void jitterSpreadsWaitsEitherSideOfTheCurveWithoutLeavingTheBand() {
    RetryPolicy policy =
        new RetryPolicy.Exponential(
            50, Duration.ofSeconds(10), 2.0, Duration.ofSeconds(3), Duration.ofHours(1));
    RandomGenerator random = new Random(42);

    var waits = IntStream.range(0, 500).mapToObj(i -> backoffOf(policy.decide(1, random))).toList();

    assertThat(waits)
        .allSatisfy(
            wait -> assertThat(wait).isBetween(Duration.ofSeconds(7), Duration.ofSeconds(13)));
    assertThat(waits.stream().distinct().count())
        .as("identical waits would defeat the point -- retries would stay in lockstep")
        .isGreaterThan(100L);
  }

  @Test
  void jitterNeverProducesANegativeWait() {
    RetryPolicy policy =
        new RetryPolicy.FixedDelay(50, Duration.ofMillis(10), Duration.ofSeconds(5));
    RandomGenerator random = new Random(7);

    assertThat(IntStream.range(0, 500).mapToObj(i -> backoffOf(policy.decide(1, random))))
        .allSatisfy(wait -> assertThat(wait).isGreaterThanOrEqualTo(Duration.ZERO));
  }

  @Test
  void aJitteredWaitStillCannotExceedTheCap() {
    RetryPolicy policy =
        new RetryPolicy.Exponential(
            50, Duration.ofSeconds(30), 2.0, Duration.ofSeconds(10), Duration.ofSeconds(30));
    RandomGenerator random = new Random(3);

    assertThat(IntStream.range(0, 500).mapToObj(i -> backoffOf(policy.decide(5, random))))
        .as("jitter applied after the cap would quietly exceed it")
        .allSatisfy(wait -> assertThat(wait).isLessThanOrEqualTo(Duration.ofSeconds(30)));
  }

  @Test
  void aSeededGeneratorMakesTheDecisionReproducible() {
    RetryPolicy policy =
        new RetryPolicy.Exponential(
            5, Duration.ofSeconds(10), 2.0, Duration.ofSeconds(3), Duration.ofMinutes(1));

    assertThat(policy.decide(2, new Random(99)))
        .as("a policy holding its own generator could not be pinned like this")
        .isEqualTo(policy.decide(2, new Random(99)));
  }

  @Test
  void aBudgetBelowOneIsRaisedRatherThanCreatingAnEffectNothingCanRun() {
    assertThat(
            new RetryPolicy.FixedDelay(0, Duration.ofSeconds(1), Duration.ZERO).decide(1, UNUSED))
        .isInstanceOf(RetryDecision.GiveUp.class);
    assertThat(
            new RetryPolicy.FixedDelay(0, Duration.ofSeconds(1), Duration.ZERO).decide(0, UNUSED))
        .isInstanceOf(RetryDecision.RetryAfter.class);
  }
}
