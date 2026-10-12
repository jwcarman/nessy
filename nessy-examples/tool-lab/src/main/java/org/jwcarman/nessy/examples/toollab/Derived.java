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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.ToolOffer;

/**
 * Which tools are offered on a call, as a pure function of the context being sent.
 *
 * <p>The tools offered are the variant's meta tools, then every catalog tool named by a tool call
 * anywhere in the turns, then every catalog tool named in the {@code names} of a {@code load_tools}
 * call whose outcome was {@code Succeeded}. A tool is listed once, where it first appears. Nothing
 * is remembered between calls: what was loaded is read back out of the history.
 *
 * <p>Version 0 offers the whole catalog and nothing else.
 */
final class Derived {

  private Derived() {}

  /** The tools to offer with {@code turns} as the context. */
  static List<ToolOffer> offers(Variant variant, Catalog catalog, List<Turn> turns) {
    if (variant == Variant.V0) {
      return catalog.tools().stream().map(CatalogTool::offer).toList();
    }
    List<ToolOffer> offers = new ArrayList<>(variant.metaTools());
    for (String name : catalogNames(catalog, turns)) {
      catalog.find(name).ifPresent(tool -> offers.add(tool.offer()));
    }
    return List.copyOf(offers);
  }

  /** The catalog tools the history names or loads, in the order it does. */
  static Set<String> catalogNames(Catalog catalog, List<Turn> turns) {
    Set<String> named = new LinkedHashSet<>();
    for (Turn turn : turns) {
      for (Exchange exchange : turn.exchanges()) {
        Set<CallId> succeeded = succeededIds(exchange);
        for (Block.ActionRequestContent block : exchange.request()) {
          if (block instanceof Block.ToolCall call) {
            String name = call.name().value();
            if (name.equals(MetaTools.LOAD) && succeeded.contains(call.id())) {
              for (String loaded : MetaTools.namesIn(call.arguments())) {
                addIfCatalog(catalog, named, loaded);
              }
            } else {
              addIfCatalog(catalog, named, name);
            }
          }
        }
      }
    }
    return named;
  }

  private static void addIfCatalog(Catalog catalog, Set<String> named, String name) {
    if (catalog.find(name).isPresent()) {
      named.add(name);
    }
  }

  private static Set<CallId> succeededIds(Exchange exchange) {
    Set<CallId> ids = new HashSet<>();
    for (ToolOutcome outcome : exchange.outcomes()) {
      if (outcome instanceof ToolOutcome.Succeeded) {
        ids.add(outcome.callId());
      }
    }
    return ids;
  }
}
