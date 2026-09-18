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
package org.jwcarman.nessy.approval.intent;

import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/** The rule that a declaration is a precondition -- never, on its own, a reason to allow. */
public final class IntentPolicy {

  private IntentPolicy() {}

  /**
   * Refuses a call whose agent has declared nothing, and otherwise defers to {@code next}.
   *
   * <p>The enricher runs first so the guarded approver sees the same fact this policy judged on;
   * the denial names the tool to use, because a model that is told only "no" cannot correct.
   */
  public static Approver requireDeclared(IntentEnricher<?> enricher, Approver next) {
    Objects.requireNonNull(enricher, "enricher must not be null");
    Objects.requireNonNull(next, "next must not be null");
    return request -> {
      enricher.enrich(request);
      return request.fact(IntentEnricher.DECLARED).isPresent()
          ? next.approve(request)
          : Awaited.ready(
              ApprovalResult.denied(
                  "no intent declared -- declare your intent with the declare-intent tool before"
                      + " acting"));
    };
  }
}
