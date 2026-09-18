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
package org.jwcarman.nessy.engine.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.spi.inference.ToolOffer;

/**
 * The tools one harness offers, by the name the model calls them.
 *
 * <p>Insertion-ordered, because the order tools are offered in is the order they are described to
 * the model, and a set that reordered between boots would change the prompt without anybody
 * changing anything.
 *
 * <p><b>A call for a name that is not here is not an error to throw.</b> A model asks for tools
 * that do not exist -- it misremembers a name, or the configuration changed between the offer and
 * the call -- and the agent is holding an obligation either way. What it needs is a result saying
 * so, which is a thing the model can read and correct.
 */
public final class Tools {

  private final Map<ToolName, ToolBinding<?>> byName;

  public Tools(List<ToolBinding<?>> tools) {
    Map<ToolName, ToolBinding<?>> map = new LinkedHashMap<>();
    for (ToolBinding<?> tool : tools) {
      if (map.putIfAbsent(tool.name(), tool) != null) {
        // Two tools under one name means the model's choice is ambiguous and which one
        // runs depends on binding order. Better to refuse at startup than to pick.
        throw new IllegalArgumentException("duplicate tool name: " + tool.name());
      }
    }
    this.byName = Map.copyOf(map);
  }

  public static Tools none() {
    return new Tools(List.of());
  }

  public Optional<ToolBinding<?>> find(ToolName name) {
    return Optional.ofNullable(byName.get(name));
  }

  public boolean isEmpty() {
    return byName.isEmpty();
  }

  /** Everything on offer, in the order it was bound. */
  public List<ToolBinding<?>> all() {
    return List.copyOf(byName.values());
  }

  /** What a provider is told, in the order tools were bound. */
  public List<ToolOffer> offers() {
    return byName.values().stream().map(ToolBinding::offer).toList();
  }
}
