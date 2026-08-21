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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The default registry: a fixed set of grants, resolved by name. */
final class DefaultToolRegistry implements ToolRegistry {

  private final Map<String, ToolGrant> grants;
  private final List<ToolGrant> ordered;
  private final List<ToolSpec> specs;

  private DefaultToolRegistry(Map<String, ToolGrant> grants) {
    this.grants = Collections.unmodifiableMap(new LinkedHashMap<>(grants));
    this.ordered = List.copyOf(this.grants.values());
    // Computed once: Tool.spec() runs reflective schema generation, and specs()
    // is called on every model round-trip.
    this.specs = this.ordered.stream().map(grant -> grant.tool().spec()).toList();
  }

  static DefaultToolRegistry of(ToolGrant... grants) {
    Map<String, ToolGrant> byName = new LinkedHashMap<>();
    for (ToolGrant grant : grants) {
      ToolGrant existing = byName.put(grant.tool().name(), grant);
      if (existing != null) {
        throw new IllegalArgumentException("duplicate tool name: " + grant.tool().name());
      }
    }
    return new DefaultToolRegistry(byName);
  }

  @Override
  public Optional<ToolGrant> find(String name) {
    return Optional.ofNullable(grants.get(name));
  }

  @Override
  public List<ToolGrant> grants() {
    return ordered;
  }

  @Override
  public List<ToolSpec> specs() {
    return specs;
  }
}
