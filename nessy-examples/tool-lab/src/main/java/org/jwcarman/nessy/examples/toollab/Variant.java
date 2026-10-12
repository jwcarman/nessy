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

import java.util.List;
import java.util.Locale;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;

/**
 * A way of showing the model the catalog: what the system prompt says and which tools are offered.
 */
enum Variant {
  /** Every catalog tool, with its full schema, on every call. */
  V0,
  /** The catalog as one line per tool in the system prompt; schemas arrive on request. */
  V1,
  /** A search over the catalog; schemas arrive on request. */
  V2;

  private static final String BASE =
      """
      You are an operations assistant for a company. You work through tools that reach the \
      company's systems. Use them to do what the user asks, and answer briefly when you are done. \
      If a request could be done in more than one system and the user did not say which, do not \
      guess: do not call a tool that creates, changes or deletes anything, and ask the user which \
      system they mean.""";

  String label() {
    return name().toLowerCase(Locale.ROOT);
  }

  static Variant parse(String label) {
    for (Variant variant : values()) {
      if (variant.label().equals(label.strip().toLowerCase(Locale.ROOT))) {
        return variant;
      }
    }
    throw new IllegalArgumentException(
        "unknown variant '%s'; choose from v0, v1, v2".formatted(label));
  }

  /** The meta tools this variant always offers, in the order offered. */
  List<ToolOffer> metaTools() {
    return switch (this) {
      case V0 -> List.of();
      case V1 -> List.of(MetaTools.loadOffer());
      case V2 -> List.of(MetaTools.searchOffer(), MetaTools.loadOffer());
    };
  }

  /** The system prompt for this variant over {@code catalog}. */
  SystemPrompt systemPrompt(Catalog catalog) {
    return switch (this) {
      case V0 -> new SystemPrompt(BASE);
      case V1 -> {
        StringBuilder prompt = new StringBuilder(BASE);
        prompt.append(
            "\n\nThe tools you can use are listed below as name — description. Their parameters"
                + " are not shown. Call load_tools with the names of the tools you need; their"
                + " full definitions are offered from your next step on, and only then can you"
                + " call them.\n\nTools:\n");
        for (CatalogTool tool : catalog.tools()) {
          prompt.append(tool.name()).append(" — ").append(tool.description()).append('\n');
        }
        yield new SystemPrompt(prompt.toString().stripTrailing());
      }
      case V2 ->
          new SystemPrompt(
              BASE
                  + "\n\nA large catalog of tools is available, but you cannot see it. Call"
                  + " search_tools with keywords to find tools, then call load_tools with the names"
                  + " you need; their full definitions are offered from your next step on, and"
                  + " only then can you call them.");
    };
  }

  /** The tools offered on the next call, given the turns so far. */
  Toolset toolset(Catalog catalog, List<Turn> turns) {
    return Toolset.of(Derived.offers(this, catalog, turns));
  }
}
