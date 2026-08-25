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
package org.jwcarman.nessy.intent;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.Rule;

/**
 * Rules over the intent an {@link IntentEnricher} deposited (approval-lifecycle spec §1.4:
 * enrichers gather, rules judge; an absent declaration is a successfully gathered fact, not an
 * enricher's own failure to report).
 */
public final class IntentRules {

  private IntentRules() {}

  /**
   * A declaration of {@code vocabulary}'s type on the request passes the ladder on; its absence
   * denies, teaching the model to declare its intent with the declare-intent tool before acting.
   *
   * @param vocabulary the declared-intent vocabulary
   * @param <T> the declared-intent vocabulary
   * @return the rule
   */
  public static <T> Rule requireDeclared(Class<T> vocabulary) {
    Objects.requireNonNull(vocabulary, "vocabulary must not be null");
    Approval denied =
        Approval.denied(
            "no "
                + vocabulary.getSimpleName()
                + " declared — declare your intent with the declare-intent tool before acting");
    return Rule.named(
        "intent declared",
        request ->
            request.facts().get(IntentEnricher.declared(vocabulary)).isPresent()
                ? new Rule.Verdict.Undecided()
                : new Rule.Verdict.Answered(denied));
  }
}
