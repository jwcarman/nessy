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
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.CompletionPolicy;

/** Which grants this agent has. */
public interface ToolRegistry {

  /** The default registry: a fixed, registration-ordered set of grants, resolved by name. */
  static ToolRegistry of(ToolGrant... grants) {
    return DefaultToolRegistry.of(grants);
  }

  /**
   * Sugar: each tool granted {@link UsagePolicy#allow()} — an answered authority, not a bare grant.
   */
  static ToolRegistry of(Tool<?>... tools) {
    ToolGrant[] grants = new ToolGrant[tools.length];
    for (int i = 0; i < tools.length; i++) {
      grants[i] = ToolGrant.grant(tools[i], UsagePolicy.allow());
    }
    return DefaultToolRegistry.of(grants);
  }

  Optional<ToolGrant> find(String name);

  /** Every grant, registration-ordered. */
  List<ToolGrant> grants();

  /** Every exposed tool's wire description, for handing to the model. */
  List<ToolSpec> specs();

  /**
   * Filtering precedes failing (spec §4.3): a view hiding every grant whose tool requires more
   * completion capability than {@code policy} offers — the model never sees what the wiring cannot
   * honor.
   */
  static ToolRegistry limited(ToolRegistry base, CompletionPolicy policy) {
    Objects.requireNonNull(base, "base must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    return new ToolRegistry() {
      @Override
      public Optional<ToolGrant> find(String name) {
        return base.find(name).filter(g -> g.tool().requiredCompletion().compareTo(policy) <= 0);
      }

      @Override
      public List<ToolGrant> grants() {
        return base.grants().stream()
            .filter(g -> g.tool().requiredCompletion().compareTo(policy) <= 0)
            .toList();
      }

      @Override
      public List<ToolSpec> specs() {
        return grants().stream().map(g -> g.tool().spec()).toList();
      }
    };
  }
}
