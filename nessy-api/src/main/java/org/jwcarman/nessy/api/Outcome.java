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

/** What came of asking. */
public sealed interface Outcome<T> {

  /**
   * The model answered.
   *
   * <p>Text when nothing was asked of the answer's shape, and the shape itself when something was
   * -- the same arm either way, because a caller that asked for an invoice wants an invoice, not an
   * invoice it has to parse.
   *
   * @param stats what the turn that produced it did and what it cost
   */
  record Answered<T>(T value, TurnStats stats) implements Outcome<T> {}

  /**
   * The model declined, and would decline again.
   *
   * @param stats what the turn that declined did and what it cost -- a refusal is not free, since
   *     the model read the input before deciding not to answer it
   */
  record Refused<T>(String category, TurnStats stats) implements Outcome<T> {}

  /**
   * The turn ended without an answer.
   *
   * <p>Carries the reason rather than a sentence, because what matters about a failed inference is
   * whether trying again could work -- which is the opposite of a failed tool call, whose message
   * exists for the model to read.
   *
   * <p>An answer that would not fit the shape it was asked for arrives here too. The turn happened
   * and the model spoke; what came back was not the thing requested, which is a failure of the
   * asking rather than a refusal by the model.
   *
   * @param stats what the turn spent before it failed, which is the reading that matters most -- a
   *     turn that failed expensively is a different problem from one that failed at once
   */
  record Failed<T>(String reason, TurnStats stats) implements Outcome<T> {}

  /**
   * Somebody else is already running a turn on this scope, so this one never started.
   *
   * <p>The only arm that means no turn happened: nothing was appended, nothing was spent, and
   * nothing about the scope changed. Which is also what makes it the only one worth simply asking
   * again for -- the other three are answers, and asking again gets another one.
   *
   * <p>Says nothing about who holds the scope or for how long. Nothing can know that honestly; only
   * that a moment ago it was taken.
   *
   * <p><b>The one arm with no tally</b>, because there was no turn to tally. Every other arm can
   * say what it cost; this one would have to invent an empty one, and an empty tally reads as a
   * turn that ran and spent nothing rather than as a turn that never was.
   */
  record Busy<T>() implements Outcome<T> {}
}
