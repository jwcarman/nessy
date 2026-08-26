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
package org.jwcarman.nessy.examples.watchman;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * {@code containers} (spec §2.1): every container the host knows about, with the unhealthy and
 * exited ones called out.
 *
 * <p>{@code -a}, not the bare {@code docker ps} of the spec's sketch, because a container that
 * exited is exactly the thing this tool exists to notice, and {@code docker ps} without it hides
 * precisely those. {@code --format json} emits one JSON object per line — not a JSON array — so it
 * is read a line at a time.
 */
public final class Containers {

  /** No input: every container there is. */
  public record Inventory() {}

  private static final List<String> ARGV = List.of("docker", "ps", "-a", "--format", "json");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Containers() {}

  /** The tool, over the host seam. */
  public static Tool<Inventory> tool(CommandRunner runner) {
    Objects.requireNonNull(runner, "runner must not be null");
    return Tool.of(
        Inventory.class,
        t ->
            t.name("containers")
                .description(
                    "Lists every Docker container with its state, flagging the ones that are"
                        + " unhealthy or exited.")
                .executes(input -> report(runner.run(ARGV))));
  }

  static String report(CommandRunner.Output output) {
    if (!output.succeeded()) {
      return "docker failed: " + output.text().strip();
    }
    List<String> lines = new ArrayList<>();
    for (String line : output.stdout().lines().map(String::strip).toList()) {
      if (!line.isEmpty()) {
        lines.add(describe(line));
      }
    }
    return lines.isEmpty() ? "no containers" : String.join("\n", lines);
  }

  private static String describe(String line) {
    JsonNode container = parse(line);
    if (container == null) {
      return "unparseable docker output: " + line;
    }
    String name = text(container, "Names");
    String state = text(container, "State");
    String status = text(container, "Status");
    String flag = attention(state, status) ? " <-- needs attention" : "";
    return name + " " + state + " (" + status + ")" + flag;
  }

  private static boolean attention(String state, String status) {
    String lowered = (state + " " + status).toLowerCase(Locale.ROOT);
    return lowered.contains("exited") || lowered.contains("unhealthy") || lowered.contains("dead");
  }

  private static JsonNode parse(String line) {
    try {
      return MAPPER.readTree(line);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static String text(JsonNode container, String field) {
    JsonNode value = container.get(field);
    return value == null || value.isNull() ? "unknown" : value.asText();
  }
}
