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
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationReport;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.RiskPolicies;

/**
 * Canonical {@link UsagePolicy} factories that judge {@link AuthzContext#declaredIntent()}
 * (vocabulary amendment §3: "Enforcement is a policy, never an enricher" — enrichers gather,
 * policies judge; an absent declaration is a successfully gathered fact, not an enricher's own
 * failure to report).
 */
public final class IntentPolicies {

  private IntentPolicies() {}

  /**
   * Denies unless {@link AuthzContext#declaredIntent(Class)} of {@code vocabulary}'s type is on the
   * context — absence and a wrong-typed declaration both deny the same way, teaching the model to
   * declare its intent with the declare-intent tool before acting. Presence of the right type
   * allows.
   *
   * <p>This reads the context on every call — deliberately not a {@link UsagePolicy.Static}, since
   * its verdict depends on whatever the intent enricher deposited.
   */
  public static UsagePolicy requireDeclared(Class<?> vocabulary) {
    Objects.requireNonNull(vocabulary, "vocabulary must not be null");
    return new RequireDeclaredPolicy(vocabulary);
  }

  /**
   * The policy {@link #requireDeclared(Class)} returns — a named class, not a bare lambda, so
   * {@link AuthorizationReport} reports it as {@code policy (RequireDeclaredPolicy)} rather than an
   * unreadable synthetic lambda class name (design of record 2026-08-16-authorization §8, echoed by
   * {@link RiskPolicies}'s own threshold policy). Package-private: {@link #requireDeclared(Class)}
   * is the only supported way to obtain one.
   */
  static final class RequireDeclaredPolicy implements UsagePolicy {

    private final Class<?> vocabulary;

    RequireDeclaredPolicy(Class<?> vocabulary) {
      this.vocabulary = vocabulary;
    }

    @Override
    public PolicyDecision evaluate(AuthzContext context) {
      return context
          .declaredIntent(vocabulary)
          .<PolicyDecision>map(declared -> new PolicyDecision.Allow())
          .orElseGet(
              () ->
                  new PolicyDecision.Deny(
                      "no "
                          + vocabulary.getSimpleName()
                          + " declared — declare your intent with the declare-intent tool before"
                          + " acting"));
    }
  }
}
