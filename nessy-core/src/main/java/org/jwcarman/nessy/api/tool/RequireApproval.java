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

import org.jwcarman.nessy.api.tool.authorization.AuthzContext;

/**
 * The canonical singleton {@link UsagePolicy#requireApproval()} returns. A named final class, not a
 * bare lambda, purely so {@code AuthorizationReport}'s policy story can recognize it and print its
 * own canonical factory name ({@code requireApproval()}) rather than an unreadable synthetic lambda
 * token — the same motivation {@link Allow} and {@link Deny} already have. Deliberately does NOT
 * implement {@link UsagePolicy.Static}: unlike those two, its verdict still needs the tool's
 * rendered action and the assembled context handed to the approver (design §9), so it must not take
 * the chokepoint's rung-0 fast path (see {@link UsagePolicy.Static}'s own javadoc).
 *
 * <p>Package-private: nothing outside {@code org.jwcarman.nessy.api.tool} may name this type
 * directly, only reach it through {@link UsagePolicy#requireApproval()}. Since it is not {@link
 * UsagePolicy.Static}, a caller outside this package cannot even ask by type whether a policy is
 * this one — {@code AuthorizationReport} instead compares a policy against the canonical instance
 * {@link UsagePolicy#requireApproval()} returns.
 */
final class RequireApproval implements UsagePolicy<Object> {

  static final RequireApproval INSTANCE = new RequireApproval();

  private RequireApproval() {}

  @Override
  public PolicyDecision evaluate(AuthzContext context, Object action) {
    return new PolicyDecision.RequireApproval();
  }
}
