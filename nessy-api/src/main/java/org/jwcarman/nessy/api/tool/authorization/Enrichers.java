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
package org.jwcarman.nessy.api.tool.authorization;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Canonical {@link Enricher} factories — the principal kit (action-wave spec §4). Nessy never
 * imposes an identity shape; authorization here is never authentication, so {@code resolver} hands
 * over an already-authenticated identity of whatever type the deployment prefers.
 */
public final class Enrichers {

  private Enrichers() {}

  /**
   * Deposits {@code resolver}'s result under {@link AuthzContext#PRINCIPAL_KEY}; named "principal"
   * for {@link AuthorizationReport}.
   */
  public static Enricher principal(Supplier<?> resolver) {
    Objects.requireNonNull(resolver, "resolver must not be null");
    return Enricher.named(
        "principal", context -> context.with(AuthzContext.PRINCIPAL_KEY, resolver.get()));
  }
}
