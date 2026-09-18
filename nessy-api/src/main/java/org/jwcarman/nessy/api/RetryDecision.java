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
