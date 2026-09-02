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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;

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
            // -P (POSIX output) guarantees one line per filesystem AND forces 512-byte blocks on
            // BSD/macOS, silently overriding -h; the model then sees a unit-less number it can't
            // reason about (see diskUsage/mount below). We drop -P for real units and make the
            // parsing robust to what that costs: BSD may wrap a very long device name onto its
            // own line, and BSD (unlike GNU) prints three extra inode columns by default.
            args -> List.of("df", "-h"),
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

  /**
   * The watchman's tools, as {@link Tool}s bound to the runner that executes them. The engine never
   * learns that these happen to be shell commands.
   *
   * <p>Every tool takes NO arguments — the read-only ones report everything, and the two acting
   * ones have exactly one thing they do — so each binds {@link JsonNode} and ignores it. That keeps
   * this example about composition rather than JSON-schema plumbing.
   */
  public static List<Tool<JsonNode>> boundTo(CommandRunner runner) {
    return SPECS.values().stream().map(spec -> toTool(spec, runner)).toList();
  }

  private static Tool<JsonNode> toTool(Spec spec, CommandRunner runner) {
    return new Tool<>() {
      @Override
      public String name() {
        return spec.name();
      }

      @Override
      public String description() {
        return spec.description();
      }

      @Override
      public Class<JsonNode> inputType() {
        return JsonNode.class;
      }

      @Override
      public ObjectNode inputSchema() {
        return emptyObjectSchema();
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<JsonNode> call) {
        JsonNode input = call.input();
        // Blocking by design; the engine runs this on its blocking executor.
        List<String> argv = spec.argv().apply(input == null ? JSON.createObjectNode() : input);
        CommandRunner.Output output = runner.run(argv, spec.timeout());
        String rendered = spec.render().apply(output, argv);
        return Awaited.ready(
            output.succeeded() ? ToolResult.ok(rendered) : ToolResult.error(rendered));
      }
    };
  }

  /** What running this tool would actually do, as a person would read it. */
  public static String actionOf(String tool, JsonNode arguments) {
    return spec(tool)
        .map(spec -> String.join(" ", spec.argv().apply(arguments)))
        .orElse("(unknown tool " + tool + ")");
  }

  public static boolean needsApproval(String tool) {
    return spec(tool).map(Spec::needsApproval).orElse(false);
  }

  private static ObjectNode emptyObjectSchema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    schema.putObject("properties");
    schema.putArray("required");
    return schema;
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

  // Matches a Capacity column: "24%", "100%", or the "-" df prints when a filesystem has no
  // notion of capacity (e.g. some macOS synthetic mounts under -i).
  private static final Pattern CAPACITY = Pattern.compile("^-?\\d+%$|^-$");

  // Leading numeric portion of a size column ("0Bi", "203Ki", "1.0G"), used only to catch the
  // zero-capacity autofs placeholders that don't already say "map" or "devfs".
  private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)?)");

  static String diskUsage(CommandRunner.Output output) {
    if (!output.succeeded()) {
      return "df failed: " + output.text().strip();
    }
    List<String> reports = new ArrayList<>();
    String carry = null;
    for (String raw : output.stdout().lines().skip(1).toList()) {
      String line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      // A device name so long df wrapped it onto its own line, with the rest of the row (and no
      // filesystem column) following on the next one. This can only happen without -P.
      if (!line.matches(".*\\s.*")) {
        carry = line;
        continue;
      }
      String row = carry == null ? line : carry + " " + line;
      carry = null;
      String reported = mount(row);
      if (reported != null) {
        reports.add(reported);
      }
    }
    return reports.isEmpty() ? "no filesystems reported" : String.join("\n", reports);
  }

  private static String mount(String line) {
    String[] columns = line.trim().split("\\s+");
    // Find Capacity by pattern, not fixed position: BSD's df -h inserts iused/ifree/%iused
    // columns between Capacity and Mounted-on that GNU's does not (and %iused itself ends in
    // "%", so we must take the FIRST match scanning left to right, not the last), and a
    // two-word filesystem name ("map auto_home") shifts every column that follows it.
    int capacityIndex = -1;
    for (int i = 3; i < columns.length; i++) {
      if (CAPACITY.matcher(columns[i]).matches()) {
        capacityIndex = i;
        break;
      }
    }
    if (capacityIndex < 0) {
      return null;
    }
    String filesystem = String.join(" ", Arrays.copyOfRange(columns, 0, capacityIndex - 3));
    String size = columns[capacityIndex - 3];
    String used = columns[capacityIndex - 2];
    String avail = columns[capacityIndex - 1];
    String capacity = columns[capacityIndex];
    String mountedOn =
        String.join(" ", Arrays.copyOfRange(columns, capacityIndex + 1, columns.length));
    if (isPseudoFilesystem(filesystem, size)) {
      return null;
    }
    return mountedOn + " " + capacity + " used, " + avail + " free";
  }

  /**
   * A filesystem the model can do nothing about, and that would bury a real alarm if reported.
   * {@code devfs} is a virtual device-node filesystem, permanently 100% full by design. A {@code
   * map ...} entry is an autofs placeholder, never a real mount. A total size of 0 means there is
   * nothing to free regardless of name — this also covers Linux pseudo-filesystems like an empty
   * {@code proc} or {@code sysfs}. Deliberately NOT "keep only /dev/*": a genuinely full Linux
   * {@code tmpfs} (e.g. {@code /run}) is a real problem this tool must still report.
   */
  private static boolean isPseudoFilesystem(String filesystem, String size) {
    return filesystem.equals("devfs") || filesystem.startsWith("map") || isZeroSize(size);
  }

  private static boolean isZeroSize(String size) {
    Matcher matcher = LEADING_NUMBER.matcher(size);
    return matcher.find() && Double.parseDouble(matcher.group(1)) == 0.0;
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
