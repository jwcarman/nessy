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
public sealed interface Outcome {

  /** The model answered. */
  record Answered(String text) implements Outcome {}

  /** The model declined, and would decline again. */
  record Refused(String category) implements Outcome {}

  /**
   * The turn ended without an answer.
   *
   * <p>Carries the reason rather than a sentence, because what matters about a failed inference is
   * whether trying again could work -- which is the opposite of a failed tool call, whose message
   * exists for the model to read.
   */
  record Failed(String reason) implements Outcome {}

  /**
   * Somebody else is already running a turn on this scope, so this one never started.
   *
   * <p>The only arm that means no turn happened: nothing was appended, nothing was spent, and
   * nothing about the scope changed. Which is also what makes it the only one worth simply asking
   * again for -- the other three are answers, and asking again gets another one.
   *
   * <p>Says nothing about who holds the scope or for how long. Nothing can know that honestly; only
   * that a moment ago it was taken.
   */
  record Busy() implements Outcome {}
}
