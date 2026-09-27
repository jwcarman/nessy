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

import java.time.Instant;

/**
 * Whether a turn should carry on, and what to do about it if not.
 *
 * <p>Consulted where the engine would otherwise ask the model again, so a turn is bounded at the
 * one point it could go round forever. Per-effect terms already bound a single call; nothing before
 * this bounded the loop, and a turn that keeps calling tools is unbounded in both time and spend
 * with every individual call inside its budget.
 *
 * <p><b>A function rather than data.</b> {@link RetryPolicy} is sealed and serialisable because it
 * is frozen onto an effect row and read back later; this is read from configuration at the moment
 * it is needed and never stored, so it can be anything an application can write. What ships is one
 * implementation, {@link #calls}, because a default has to be nameable. A second unit, two
 * thresholds in different units, or two policies at once is a lambda -- which is what the function
 * form is for. Helpers can follow once somebody has written the same one twice.
 *
 * @see TurnStats for what it decides on
 */
@FunctionalInterface
public interface TurnPolicy {

  /**
   * @param now for a policy that cares about elapsed time, which {@link TurnStats} cannot carry --
   *     a stored duration would be measured against whatever clock was running at replay
   * @return what to do, never null
   */
  TurnDecision decide(TurnStats stats, Instant now);

  /** No bound at all. A turn runs until it finishes or something else stops it. */
  static TurnPolicy unbounded() {
    return (stats, now) -> new TurnDecision.Continue();
  }

  /**
   * A bound on how many times a turn may call the model.
   *
   * <p><b>Both thresholds read as "at or past", never as equality.</b> The count does not advance
   * once per consultation -- a tool completing moves a turn on without calling the model -- so a
   * policy asking whether it <em>is</em> twenty could be consulted at nineteen, then at twenty-one,
   * and never fire.
   *
   * <p>Model calls rather than tool calls, because model calls are what cost money. Time and spend
   * are perfectly good things to bound on; they are not things a shipped default may assume, so
   * they are written as a lambda by whoever knows their own numbers.
   *
   * @param answerAt where the model is asked to answer from what it has, which takes nothing away
   * @param failAt where the turn ends, which exists because the mechanism behind {@code answerAt}
   *     is honoured differently by each vendor and a model that ignores it would otherwise loop
   * @throws IllegalArgumentException if the thresholds are not in that order, since two counts of
   *     the same type invite being passed the wrong way round
   */
  static TurnPolicy calls(int answerAt, int failAt) {
    if (answerAt < 1) {
      throw new IllegalArgumentException("answerAt must be at least one call: " + answerAt);
    }
    if (failAt <= answerAt) {
      throw new IllegalArgumentException(
          "failAt must be past answerAt, or one of them never happens: "
              + answerAt
              + " and "
              + failAt);
    }
    return (stats, now) -> {
      if (stats.modelCalls() >= failAt) {
        return new TurnDecision.FailTurn(
            "the turn made " + stats.modelCalls() + " model calls without finishing");
      }
      if (stats.modelCalls() >= answerAt) {
        return new TurnDecision.AnswerNow();
      }
      return new TurnDecision.Continue();
    };
  }
}
