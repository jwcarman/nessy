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

import java.util.Objects;

/**
 * What to do about a turn that has been going on.
 *
 * <p>Three imperatives the engine obeys, in increasing order of how much they take away. Mirrors
 * {@link RetryPolicy} and its {@link RetryDecision}: a policy is asked, and what comes back is a
 * decision rather than an instruction the caller has to interpret.
 */
public sealed interface TurnDecision {

  /** Carry on. The overwhelmingly common answer, and what a turn nobody bounded always gets. */
  record Continue() implements TurnDecision {}

  /**
   * Ask the model to answer, and offer it no tools this time.
   *
   * <p><b>The response that does not destroy work.</b> A turn deep in a loop cannot be told apart
   * from a long honest one -- research and multi-file work genuinely run long -- so the first thing
   * a bound does is ask for an answer rather than take the turn away. A turn that was fine returns
   * a real answer, slightly early; one that was stuck stops spending. A caller still receives
   * {@link Outcome.Answered}.
   */
  record AnswerNow() implements TurnDecision {}

  /**
   * End the turn, with this reason.
   *
   * <p>Named for what it produces: the fact is an {@code AgentEvent.TurnStopped}, a watcher hears
   * {@link Narration.TurnStopped}, and a caller receives {@link Outcome.Failed}.
   *
   * <p>Distinct from {@link RetryDecision.GiveUp}, which is about one call rather than a whole
   * turn, and carries no reason because a caller of that one never sees it.
   */
  record FailTurn(String reason) implements TurnDecision {
    public FailTurn {
      Objects.requireNonNull(reason, "reason must not be null");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("a turn ended on purpose must say why");
      }
    }
  }
}
