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
package org.jwcarman.nessy.spi.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Retries the OPENING of a model stream, with exponential backoff.
 *
 * <p>A decorator, not loop machinery — the upgrade path is {@code wrap(provider, …)} and nothing
 * else changes. Only the initial {@link ModelProvider#stream} call is retried: once events flow,
 * tokens have already been fed downstream and a mid-stream failure propagates, because a
 * transparent re-call would replay the turn from the top.
 *
 * <p>Which failures are retryable is provider-specific (a 429 is not an auth error), so each
 * provider module publishes its own predicate — see {@code AnthropicModelProvider#RETRYABLE} and
 * {@code OpenAiModelProvider#RETRYABLE}.
 */
public final class RetryingModelProvider implements ModelProvider {

  private final ModelProvider delegate;
  private final RetryPolicy policy;
  private final Predicate<RuntimeException> retryable;
  private final Sleeper sleeper;

  public static RetryingModelProvider wrap(
      ModelProvider delegate, RetryPolicy policy, Predicate<RuntimeException> retryable) {
    return new RetryingModelProvider(delegate, policy, retryable, Sleeper.REAL);
  }

  RetryingModelProvider(
      ModelProvider delegate,
      RetryPolicy policy,
      Predicate<RuntimeException> retryable,
      Sleeper sleeper) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.retryable = Objects.requireNonNull(retryable, "retryable must not be null");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    Duration delay = policy.initialDelay();
    for (int attempt = 1; ; attempt++) {
      try {
        return delegate.stream(request);
      } catch (RuntimeException e) {
        if (attempt >= policy.maxAttempts() || !retryable.test(e)) {
          throw e;
        }
        sleeper.sleep(delay);
        delay = Duration.ofNanos((long) (delay.toNanos() * policy.multiplier()));
      }
    }
  }

  @Override
  public Set<Capability> capabilities() {
    return delegate.capabilities();
  }

  @Override
  public String name() {
    return delegate.name();
  }
}
