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
 * The canonical singleton {@link UsagePolicy#allow()} returns. Package-private: nothing outside
 * {@code org.jwcarman.nessy.api.tool} may name this type directly — the canonical instance is
 * reachable only through {@link UsagePolicy#allow()}, and {@link UsagePolicy.Static} stays the
 * public door for asking whether a policy is one of the two canonical statics without being able to
 * name which.
 */
final class Allow implements UsagePolicy<Object>, UsagePolicy.Static {

  static final Allow INSTANCE = new Allow();

  private Allow() {}

  @Override
  public PolicyDecision evaluate(AuthzContext context, Object action) {
    return decision();
  }

  @Override
  public PolicyDecision decision() {
    return new PolicyDecision.Allow();
  }
}
