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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * {@code disk_usage} (spec §2.1): {@code df -hP} parsed down to a percentage per mount.
 *
 * <p>Parsed rather than passed through, because the raw table is six columns of which the model
 * needs two, and because the parsing is the part worth a test. {@code -P} is load-bearing: POSIX
 * output format keeps one filesystem on one line, and without it a long device name wraps and every
 * column shifts.
 */
public final class DiskUsage {

  /** No input: the tool reports every mount there is. */
  public record Mounts() {}

  private static final List<String> ARGV = List.of("df", "-hP");

  private DiskUsage() {}

  /** The tool, over the host seam. */
  public static Tool<Mounts> tool(CommandRunner runner) {
    Objects.requireNonNull(runner, "runner must not be null");
    return Tool.of(
        Mounts.class,
        t ->
            t.name("disk_usage")
                .description(
                    "Reports the used percentage and free space of every mounted filesystem.")
                .executes(input -> report(runner.run(ARGV))));
  }

  static String report(CommandRunner.Output output) {
    if (!output.succeeded()) {
      return "df failed: " + output.text().strip();
    }
    List<String> lines =
        output.stdout().lines().skip(1).map(DiskUsage::describe).filter(Objects::nonNull).toList();
    return lines.isEmpty() ? "no filesystems reported" : String.join("\n", lines);
  }

  /**
   * One {@code df} row as {@code <mount> <use%> used, <avail> free}, or {@code null} for a row that
   * is not a filesystem line at all — {@code df} on a busy box happily prints warnings.
   */
  private static String describe(String line) {
    String[] columns = line.trim().split("\\s+");
    if (columns.length < 6 || !columns[4].endsWith("%")) {
      return null;
    }
    return columns[5] + " " + columns[4] + " used, " + columns[3] + " free";
  }
}
