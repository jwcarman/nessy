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
package org.jwcarman.nessy.api.tool;

/**
 * What a {@link UsagePolicy} decided about one call.
 *
 * <p>Three answers, not two: {@link Allow} and {@link Deny} settle the question outright, and
 * {@link RequireApproval} defers it to the harness's {@code Approver}.
 */
public sealed interface PolicyDecision {

  /** Run it. The approver is never consulted. */
  record Allow() implements PolicyDecision {}

  /** Do not run it. {@code reason} goes into the model-visible result, so it must say something. */
  record Deny(String reason) implements PolicyDecision {

    public Deny {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }

  /** Ask a human. */
  record RequireApproval() implements PolicyDecision {}
}
