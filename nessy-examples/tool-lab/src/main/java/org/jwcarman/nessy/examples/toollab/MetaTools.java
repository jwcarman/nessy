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
import java.util.List;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.examples.toollab.Schemas.Prop;
import org.jwcarman.nessy.inference.ToolOffer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two tools the lab runs itself. They are not catalog tools: one finds tools by keyword, the
 * other names the tools a model wants to see in full.
 */
final class MetaTools {

  static final String SEARCH = "search_tools";
  static final String LOAD = "load_tools";

  /** The most tools one search returns. */
  static final int MAX_LIMIT = 25;

  /** How many tools a search returns when it is not told. */
  static final int DEFAULT_LIMIT = 10;

  private MetaTools() {}

  static ToolOffer searchOffer() {
    return new ToolOffer(
        new ToolName(SEARCH),
        "Search the tool catalog by keyword. Returns the names and descriptions of the best"
            + " matches; load a tool with load_tools before calling it.",
        Schemas.object(
            Prop.required("query", "string", "Keywords describing what you want to do."),
            Prop.optional(
                "limit",
                "integer",
                "How many results to return. Default %d, at most %d."
                    .formatted(DEFAULT_LIMIT, MAX_LIMIT))));
  }

  static ToolOffer loadOffer() {
    return new ToolOffer(
        new ToolName(LOAD),
        "Load tools by name so their full definitions are available on your next step. A tool"
            + " must be loaded before you can call it.",
        Schemas.object(Prop.required("names", "array", "The names of the tools to load.")));
  }

  static boolean isMeta(String name) {
    return name.equals(SEARCH) || name.equals(LOAD);
  }

  /**
   * The answer to {@code search_tools}.
   *
   * @throws IllegalArgumentException when there is no query
   */
  static String search(JsonNode arguments, Bm25 index) {
    String query = arguments.path("query").asString("");
    if (query.isBlank()) {
      throw new IllegalArgumentException("query is required");
    }
    int limit = arguments.path("limit").asInt(DEFAULT_LIMIT);
    limit = Math.max(1, Math.min(MAX_LIMIT, limit));
    ArrayNode found = Schemas.MAPPER.createArrayNode();
    for (CatalogTool tool : index.search(query, limit)) {
      ObjectNode entry = found.addObject();
      entry.put("name", tool.name());
      entry.put("description", tool.description());
    }
    return Schemas.MAPPER.writeValueAsString(found);
  }

  /**
   * The answer to {@code load_tools}: a line for each name, saying it is loaded or that there is no
   * such tool.
   *
   * @throws IllegalArgumentException when there are no names
   */
  static String load(JsonNode arguments, Catalog catalog) {
    JsonNode names = arguments.path("names");
    if (!names.isArray() || names.isEmpty()) {
      throw new IllegalArgumentException("names must be a non-empty array of tool names");
    }
    ArrayNode answer = Schemas.MAPPER.createArrayNode();
    for (JsonNode name : names) {
      ObjectNode entry = answer.addObject();
      String wanted = name.asString("");
      entry.put("name", wanted);
      catalog
          .find(wanted)
          .ifPresentOrElse(
              tool -> {
                entry.put("description", tool.description());
                entry.put("loaded", true);
              },
              () -> entry.put("error", "no such tool"));
    }
    return Schemas.MAPPER.writeValueAsString(answer);
  }

  /**
   * The tool names in the {@code names} argument of a {@code load_tools} call; none if unreadable.
   */
  static List<String> namesIn(String arguments) {
    List<String> names = new ArrayList<>();
    try {
      for (JsonNode name : Schemas.MAPPER.readTree(arguments).path("names")) {
        if (name.isString()) {
          names.add(name.asString());
        }
      }
    } catch (RuntimeException unreadable) {
      return List.of();
    }
    return names;
  }
}
