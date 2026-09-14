package org.jwcarman.nessy.api;

import java.time.Duration;

/**
 * What to do about an attempt that did not produce an outcome.
 *
 * <p>A decision rather than a number, so that no caller has to know what a budget is. A policy
 * counting attempts and a policy watching a deadline answer the same question, and the drainer
 * cannot tell them apart -- which is the point.
 */
public sealed interface RetryDecision {

  /** Try again once the backoff has passed. */
  record RetryAfter(Duration backoff) implements RetryDecision {}

  /** Stop. The outcome, or the absence of one, is final and the agent must be told. */
  record GiveUp() implements RetryDecision {}
}
