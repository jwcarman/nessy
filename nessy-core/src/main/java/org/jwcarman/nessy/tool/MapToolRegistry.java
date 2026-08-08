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
package org.jwcarman.nessy.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The default registry: a fixed set of tools, resolved by name. */
public final class MapToolRegistry implements ToolRegistry {

  private final Map<String, Tool<?>> tools;
  private final List<ToolSpec> specs;

  private MapToolRegistry(Map<String, Tool<?>> tools) {
    this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    // Computed once: Tool.spec() runs reflective schema generation, and specs()
    // is called on every model round-trip.
    this.specs = this.tools.values().stream().map(Tool::spec).toList();
  }

  public static MapToolRegistry of(Tool<?>... tools) {
    Map<String, Tool<?>> byName = new LinkedHashMap<>();
    for (Tool<?> tool : tools) {
      Tool<?> existing = byName.put(tool.name(), tool);
      if (existing != null) {
        throw new IllegalArgumentException("duplicate tool name: " + tool.name());
      }
    }
    return new MapToolRegistry(byName);
  }

  @Override
  public Optional<Tool<?>> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  @Override
  public List<ToolSpec> specs() {
    return specs;
  }
}
