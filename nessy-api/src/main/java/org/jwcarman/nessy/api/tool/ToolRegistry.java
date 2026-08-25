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
import org.jwcarman.nessy.api.tool.approval.Approvers;

/** Which grants this agent has. */
public interface ToolRegistry {

  /** The default registry: a fixed, registration-ordered set of grants, resolved by name. */
  static ToolRegistry of(ToolGrant... grants) {
    return DefaultToolRegistry.of(grants);
  }

  /**
   * Sugar: each tool granted {@link Approvers#allow()} — an answered authority, not a bare grant.
   */
  static ToolRegistry of(Tool<?>... tools) {
    ToolGrant[] grants = new ToolGrant[tools.length];
    for (int i = 0; i < tools.length; i++) {
      grants[i] = ToolGrant.grant(tools[i], Approvers.allow());
    }
    return DefaultToolRegistry.of(grants);
  }

  /**
   * The zero-tool registry — also resolves the empty-varargs ambiguity between the two {@code of}
   * overloads.
   */
  static ToolRegistry empty() {
    return DefaultToolRegistry.of();
  }

  Optional<ToolGrant> find(String name);

  /** Every grant, registration-ordered. */
  List<ToolGrant> grants();

  /** Every exposed tool's wire description, for handing to the model. */
  List<ToolSpec> specs();

  /**
   * Filtering precedes failing (spec §4.3): a view hiding every grant whose tool requires more
   * completion capability than {@code policy} offers — the model never sees what the wiring cannot
   * honor. {@code base} and {@code policy} are both fixed, so the filtered grants and specs are
   * computed once here rather than on every {@link #find}/{@link #grants}/{@link #specs} call
   * ({@link DefaultToolRegistry} precomputes its own {@code specs} for the same reason: {@link
   * Tool#spec()} runs reflective schema generation, and {@code specs()} is called on every model
   * round-trip).
   */
  static ToolRegistry limited(ToolRegistry base, CompletionPolicy policy) {
    Objects.requireNonNull(base, "base must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    List<ToolGrant> filteredGrants =
        base.grants().stream()
            .filter(g -> g.tool().requiredCompletion().compareTo(policy) <= 0)
            .toList();
    List<ToolSpec> filteredSpecs = filteredGrants.stream().map(g -> g.tool().spec()).toList();
    return new ToolRegistry() {
      @Override
      public Optional<ToolGrant> find(String name) {
        return filteredGrants.stream().filter(g -> g.tool().name().equals(name)).findFirst();
      }

      @Override
      public List<ToolGrant> grants() {
        return filteredGrants;
      }

      @Override
      public List<ToolSpec> specs() {
        return filteredSpecs;
      }
    };
  }
}
