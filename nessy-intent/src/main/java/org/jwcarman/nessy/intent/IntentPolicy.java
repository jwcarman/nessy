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
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/**
 * Policies over the intent an {@link IntentEnricher} recorded: enrichers gather, policies judge. An
 * absent declaration is a successfully gathered fact, not an enricher's own failure to report, so
 * deciding what to do about one belongs here.
 */
public final class IntentPolicy {

  private IntentPolicy() {}

  /**
   * Denies any call made without a declaration on the request, teaching the model to declare its
   * intent with the declare-intent tool before acting. A declared intent passes to {@code next}.
   *
   * <p>Takes the approver it guards rather than answering {@code approved} itself: "intent was
   * declared" is a precondition, never on its own a reason to allow something. An approver that
   * returned approval here would silently make a policy ladder's first rung its last.
   *
   * @param enricher the enricher that records the declaration before this policy reads it
   * @param next what decides once a declaration is present
   * @return the guarding approver
   */
  public static Approver requireDeclared(IntentEnricher<?> enricher, Approver next) {
    Objects.requireNonNull(enricher, "enricher must not be null");
    Objects.requireNonNull(next, "next must not be null");
    return (request, replyTo) -> {
      ApprovalRequest enriched = enricher.enrich(request);
      return enriched.fact(IntentEnricher.DECLARED).isPresent()
          ? next.approve(enriched, replyTo)
          : Awaited.ready(
              ApprovalResult.denied(
                  "no intent declared — declare your intent with the declare-intent tool before"
                      + " acting"));
    };
  }
}
