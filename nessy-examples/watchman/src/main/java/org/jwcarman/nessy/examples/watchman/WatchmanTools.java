package org.jwcarman.nessy.examples.watchman;

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
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The commands the watchman may run, and how what they print is told to the model. */
public final class WatchmanTools {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Every watchman tool takes no arguments, and says so rather than saying nothing. */
  private static final InputSchema NO_ARGUMENTS =
      new InputSchema("{\"type\":\"object\",\"properties\":{},\"required\":[]}");

  public record Spec(
      ToolName name,
      String description,
      boolean needsApproval,
      Duration timeout,
      List<String> argv,
      java.util.function.BiFunction<CommandRunner.Output, List<String>, String> render) {}

  private static final Map<ToolName, Spec> SPECS = new LinkedHashMap<>();

  static {
    define(
        new Spec(
            new ToolName("disk_usage"),
            "Reports the used percentage and free space of every mounted filesystem.",
            false,
            Duration.ofSeconds(30),
            // -P (POSIX output) guarantees one line per filesystem AND forces 512-byte blocks on
            // BSD/macOS, silently overriding -h; the model then sees a unit-less number it can't
            // reason about (see diskUsage/mount below). We drop -P for real units and make the
            // parsing robust to what that costs: BSD may wrap a very long device name onto its
            // own line, and BSD (unlike GNU) prints three extra inode columns by default.
            List.of("df", "-h"),
            (output, argv) -> diskUsage(output)));
    define(
        new Spec(
            new ToolName("containers"),
            "Lists every Docker container with its state, flagging the ones that are unhealthy or"
                + " exited.",
            false,
            Duration.ofSeconds(30),
            List.of("docker", "ps", "-a", "--format", "json"),
            (output, argv) -> containers(output)));
    define(
        new Spec(
            new ToolName("prune_images"),
            "Removes every unused Docker image to reclaim disk. Requires human approval; propose"
                + " it, do not expect it to run during this round.",
            true,
            Duration.ofMinutes(10),
            List.of("docker", "image", "prune", "-af"),
            WatchmanTools::plain));
    define(
        new Spec(
            new ToolName("long_job"),
            "Starts a whole-disk trim (fstrim -av). It runs for minutes; the result arrives in a"
                + " following turn.",
            false,
            Duration.ofHours(1),
            List.of("fstrim", "-av"),
            WatchmanTools::plain));
  }

  private WatchmanTools() {}

  private static void define(Spec spec) {
    SPECS.put(spec.name(), spec);
  }

  public static Optional<Spec> spec(ToolName name) {
    return Optional.ofNullable(SPECS.get(name));
  }

  public static List<Tool<JsonNode>> boundTo(CommandRunner runner) {
    return SPECS.values().stream().map(spec -> toTool(spec, runner)).toList();
  }

  private static Tool<JsonNode> toTool(Spec spec, CommandRunner runner) {
    return new Tool<>() {
      @Override
      public ToolName name() {
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
      public InputSchema inputSchema(InputSchemaGenerator generator) {
        return NO_ARGUMENTS;
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<JsonNode> request) {
        // Blocking by design; the engine runs each call on a virtual thread of its own.
        CommandRunner.Output output = runner.run(spec.argv(), spec.timeout());
        String rendered = spec.render().apply(output, spec.argv());
        // A failed command is a Failure, not a success carrying an error string: the model is
        // told plainly that nothing happened.
        return Awaited.ready(
            output.succeeded()
                ? ToolResult.ok(new Block.Text(rendered))
                : new ToolResult.Failure(rendered));
      }
    };
  }

  /** The line that will run, which is what a person is shown and consents to. */
  public static String actionOf(ToolName tool) {
    return spec(tool)
        .map(spec -> String.join(" ", spec.argv()))
        .orElse("(unknown tool " + tool.value() + ")");
  }

  public static boolean needsApproval(ToolName tool) {
    return spec(tool).map(Spec::needsApproval).orElse(false);
  }

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (JacksonException e) {
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
    String avail = columns[capacityIndex - 1];
    String capacity = columns[capacityIndex];
    String mountedOn =
        String.join(" ", Arrays.copyOfRange(columns, capacityIndex + 1, columns.length));
    if (isPseudoFilesystem(filesystem, size)) {
      return null;
    }
    return mountedOn + " " + capacity + " used, " + avail + " free";
  }

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
    return value == null || value.isNull() ? "unknown" : value.asString();
  }

  private static String text(String value) {
    return value.isBlank() ? "(no output)" : value.strip();
  }
}
