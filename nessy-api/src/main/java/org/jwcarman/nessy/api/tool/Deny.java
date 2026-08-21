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
 * What {@link UsagePolicy#deny(String)} returns: a fresh instance per reason (the reason varies, so
 * unlike {@link Allow} there is no single shared singleton), but always {@link UsagePolicy.Static}
 * — its verdict is fixed at construction, never a function of context or action. Package-private
 * for the same reason {@link Allow} is: nothing outside {@code org.jwcarman.nessy.api.tool} may
 * name this type directly, only reach it through {@link UsagePolicy#deny(String)}.
 */
final class Deny implements UsagePolicy, UsagePolicy.Static {

  private final PolicyDecision decision;

  Deny(String reason) {
    this.decision = new PolicyDecision.Deny(reason);
  }

  @Override
  public PolicyDecision evaluate(AuthzContext context) {
    return decision;
  }

  @Override
  public PolicyDecision decision() {
    return decision;
  }
}
