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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.ToolSpec;

/**
 * The four tools this port carries, and everything the rest of the system needs to know about them.
 *
 * <p>Four rather than fifteen, chosen to cover every SHAPE the real watchman has: two read-only
 * ({@code disk_usage}, {@code containers}), one behind a human ({@code prune_images}), and one that
 * runs for minutes ({@code long_job}). Adding the other eleven would add lines and no findings.
 *
 * <p>Everything about a tool lives in one {@link Spec}: its schema for the model, whether it needs
 * a human, how its command line renders for the approvals page, and how long it may take. The
 * approval policy in particular is a property of the tool rather than a rule somewhere else, which
 * is what lets {@link ToolCallActor} decide what to do by asking one question.
 */
public final class WatchmanTools {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * @param argv the literal command line, rendered from the model's arguments; this exact list is
   *     what the approvals page shows a human and what runs if they say yes
   */
  public record Spec(
      String name,
      String description,
      boolean needsApproval,
      Duration timeout,
      java.util.function.Function<JsonNode, List<String>> argv,
      java.util.function.BiFunction<CommandRunner.Output, List<String>, String> render) {}

  private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

  static {
    define(
        new Spec(
            "disk_usage",
            "Reports the used percentage and free space of every mounted filesystem.",
            false,
            Duration.ofSeconds(30),
            args -> List.of("df", "-hP"),
            (output, argv) -> diskUsage(output)));
    define(
        new Spec(
            "containers",
            "Lists every Docker container with its state, flagging the ones that are unhealthy or"
                + " exited.",
            false,
            Duration.ofSeconds(30),
            args -> List.of("docker", "ps", "-a", "--format", "json"),
            (output, argv) -> containers(output)));
    define(
        new Spec(
            "prune_images",
            "Removes every unused Docker image to reclaim disk. Requires human approval; propose"
                + " it, do not expect it to run during this round.",
            true,
            Duration.ofMinutes(10),
            args -> List.of("docker", "image", "prune", "-af"),
            WatchmanTools::plain));
    define(
        new Spec(
            "long_job",
            "Starts a whole-disk trim (fstrim -av). It runs for minutes; the result arrives in a"
                + " following turn.",
            false,
            Duration.ofHours(1),
            args -> List.of("fstrim", "-av"),
            WatchmanTools::plain));
  }

  private WatchmanTools() {}

  private static void define(Spec spec) {
    SPECS.put(spec.name(), spec);
  }

  public static Optional<Spec> spec(String name) {
    return Optional.ofNullable(SPECS.get(name));
  }

  public static boolean needsApproval(String tool) {
    return spec(tool).map(Spec::needsApproval).orElse(false);
  }

  /** The command line a human is shown, and the one that runs. Never a shell string. */
  public static String action(String tool, String argumentsJson) {
    return spec(tool)
        .map(spec -> String.join(" ", spec.argv().apply(parse(argumentsJson))))
        .orElse("(unknown tool " + tool + ")");
  }

  /** Runs one call. Blocking by design; the caller guarantees a virtual thread. */
  public static String run(CommandRunner runner, String tool, String argumentsJson) {
    Spec spec = SPECS.get(tool);
    if (spec == null) {
      return "no such tool: " + tool;
    }
    List<String> argv = spec.argv().apply(parse(argumentsJson));
    return spec.render().apply(runner.run(argv, spec.timeout()), argv);
  }

  /**
   * The tools, as Nessy's own {@link ToolSpec}. The provider turns these into whatever the wire
   * wants, so this port no longer assembles OpenAI JSON by hand.
   *
   * <p>Every tool here takes no arguments: the read-only ones report everything, and the two acting
   * ones have exactly one thing they do. That keeps the port about the actor composition rather
   * than about JSON-schema plumbing.
   */
  public static List<ToolSpec> specs() {
    return SPECS.values().stream()
        .map(spec -> new ToolSpec(spec.name(), spec.description(), emptyObjectSchema()))
        .toList();
  }

  private static ObjectNode emptyObjectSchema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    schema.putObject("properties");
    schema.putArray("required");
    return schema;
  }

  /** The model's arguments, as a node -- what a Remembrance.ToolExchange carries. */
  public static JsonNode argumentsOf(String argumentsJson) {
    return parse(argumentsJson);
  }

  private static JsonNode parse(String argumentsJson) {
    try {
      return JSON.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return JSON.createObjectNode();
    }
  }

  private static String plain(CommandRunner.Output output, List<String> argv) {
    String line = String.join(" ", argv);
    return output.succeeded()
        ? "`" + line + "` finished: " + text(output.stdout())
        : "`" + line + "` failed with exit " + output.exitCode() + ": " + text(output.text());
  }

  static String diskUsage(CommandRunner.Output output) {
    if (!output.succeeded()) {
      return "df failed: " + output.text().strip();
    }
    List<String> lines =
        output
            .stdout()
            .lines()
            .skip(1)
            .map(WatchmanTools::mount)
            .filter(line -> line != null)
            .toList();
    return lines.isEmpty() ? "no filesystems reported" : String.join("\n", lines);
  }

  private static String mount(String line) {
    String[] columns = line.trim().split("\\s+");
    if (columns.length < 6 || !columns[4].endsWith("%")) {
      return null;
    }
    return columns[5] + " " + columns[4] + " used, " + columns[3] + " free";
  }

  static String containers(CommandRunner.Output output) {
    if (!output.succeeded()) {
      return "docker failed: " + output.text().strip();
    }
    List<String> lines =
        output
            .stdout()
            .lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .map(WatchmanTools::container)
            .toList();
    return lines.isEmpty() ? "no containers" : String.join("\n", lines);
  }

  private static String container(String line) {
    JsonNode node = parse(line);
    String name = field(node, "Names");
    String state = field(node, "State");
    String status = field(node, "Status");
    String lowered = (state + " " + status).toLowerCase(Locale.ROOT);
    boolean attention =
        lowered.contains("exited") || lowered.contains("unhealthy") || lowered.contains("dead");
    return name + " " + state + " (" + status + ")" + (attention ? " <-- needs attention" : "");
  }

  private static String field(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() ? "unknown" : value.asText();
  }

  private static String text(String value) {
    return value.isBlank() ? "(no output)" : value.strip();
  }
}
