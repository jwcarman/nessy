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
package org.jwcarman.nessy.examples.toollab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A deterministic catalog of fake tools: the hand-written ones the tasks need, then generated ones
 * in a fixed order. The same size always gives the same catalog, and a smaller catalog is a prefix
 * of a larger one.
 */
final class Catalog {

  private final List<CatalogTool> tools;
  private final Map<String, CatalogTool> byName = new LinkedHashMap<>();

  private Catalog(List<CatalogTool> tools) {
    this.tools = List.copyOf(tools);
    for (CatalogTool tool : tools) {
      byName.put(tool.name(), tool);
    }
  }

  /**
   * The catalog of exactly {@code size} tools.
   *
   * @throws IllegalArgumentException when {@code size} is smaller than the hand-written tools,
   *     which are always included, or larger than the generator can make
   */
  static Catalog of(int size) {
    List<CatalogTool> hand = HandWritten.tools();
    if (size < hand.size()) {
      throw new IllegalArgumentException(
          "a catalog holds all %d hand-written tools; %d is too small"
              .formatted(hand.size(), size));
    }
    List<CatalogTool> generated = Generated.tools();
    int wanted = size - hand.size();
    if (wanted > generated.size()) {
      throw new IllegalArgumentException(
          "at most %d tools can be made; %d is too many"
              .formatted(hand.size() + generated.size(), size));
    }
    List<CatalogTool> all = new ArrayList<>(size);
    all.addAll(hand);
    all.addAll(generated.subList(0, wanted));
    return new Catalog(all);
  }

  /** The tools, hand-written first. */
  List<CatalogTool> tools() {
    return tools;
  }

  /** The tool with this name, if the catalog has one. */
  Optional<CatalogTool> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /** Every tool's name, in catalog order. */
  Set<String> names() {
    return new LinkedHashSet<>(byName.keySet());
  }
}
