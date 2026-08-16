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
package org.jwcarman.nessy.examples.newsroom;

import java.util.Locale;
import java.util.Map;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The researcher's one ordinary (ungated) tool: a lookup over a few hardcoded notes. This demo's
 * interest is the delegation mechanics — the park chain and the notebook continuity — not real
 * research, so there is no network call and no MCP dependency here; the module builds and runs
 * offline the moment a model provider key is set.
 */
final class SearchNotesTool implements Tool<SearchNotesTool.SearchNotes> {

  private static final Map<String, String> NOTES =
      Map.of(
          "octopus",
              "Octopuses have three hearts and blue, copper-based blood — two hearts pump blood"
                  + " to the gills, one to the rest of the body.",
          "volcano",
              "Iceland sits on the Mid-Atlantic Ridge and, on average, has a volcanic eruption"
                  + " every four to five years.",
          "coffee",
              "Finland has the highest per-capita coffee consumption of any country, averaging"
                  + " several cups a day per person.");

  /** What the model supplies: the topic to look up. */
  record SearchNotes(String topic) {

    SearchNotes {
      if (topic == null || topic.isBlank()) {
        throw new IllegalArgumentException("topic must not be blank");
      }
    }
  }

  @Override
  public String name() {
    return "search_notes";
  }

  @Override
  public String description() {
    return "Looks up a canned research note by topic (try: octopus, volcano, coffee). Offline,"
        + " no network access.";
  }

  @Override
  public Class<SearchNotes> inputType() {
    return SearchNotes.class;
  }

  @Override
  public String describe(SearchNotes input) {
    return "search_notes(" + input.topic() + ")";
  }

  @Override
  public Awaited<ToolResult> execute(SearchNotes input, ToolContext context) {
    String note = NOTES.get(input.topic().toLowerCase(Locale.ROOT).trim());
    return Awaited.ready(
        note != null
            ? ToolResult.ok(note)
            : ToolResult.error("no note on file for '" + input.topic() + "'"));
  }
}
