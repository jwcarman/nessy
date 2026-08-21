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

import java.util.List;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationReport;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;

/**
 * What {@link UsagePolicy#allOf(List)} returns: a named class, not a bare lambda, so {@link
 * AuthorizationReport} reports it as {@code policy (AllOfPolicy)} rather than an unreadable
 * synthetic lambda class name — the same motivation {@link Allow} and {@link Deny} are named types
 * (design of record 2026-08-16-authorization §8). Package-private: {@link UsagePolicy#allOf(List)}
 * is the only supported way to obtain one.
 *
 * <p>Deliberately does not implement {@link UsagePolicy.Static}: its verdict depends on {@code
 * policies}, and through them on context and action.
 */
final class AllOfPolicy implements UsagePolicy {

  private final List<UsagePolicy> policies;

  AllOfPolicy(List<UsagePolicy> policies) {
    this.policies = policies;
  }

  @Override
  public PolicyDecision evaluate(AuthzContext context) {
    boolean anyRequireApproval = false;
    for (UsagePolicy policy : policies) {
      PolicyDecision decision = policy.evaluate(context);
      if (decision instanceof PolicyDecision.Deny) {
        return decision;
      }
      if (decision instanceof PolicyDecision.RequireApproval) {
        anyRequireApproval = true;
      }
    }
    return anyRequireApproval ? new PolicyDecision.RequireApproval() : new PolicyDecision.Allow();
  }
}
