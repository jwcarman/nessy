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
 * {@code failed_units} (spec §2.1): systemd units in the failed state, from {@code systemctl
 * --failed}.
 *
 * <p>{@code --no-legend --plain} strips the header and the unicode bullet, so what comes back is
 * one unit per line and nothing else. An empty answer is the good answer, and it says so in words
 * rather than returning nothing — a blank tool result reads to a model like a broken tool.
 */
public final class FailedUnits {

  /** No input: every failed unit there is. */
  public record Failures() {}

  private static final List<String> ARGV =
      List.of("systemctl", "--failed", "--no-legend", "--plain", "--no-pager");

  private FailedUnits() {}

  /** The tool, over the host seam. */
  public static Tool<Failures> tool(CommandRunner runner) {
    Objects.requireNonNull(runner, "runner must not be null");
    return Tool.of(
        Failures.class,
        t ->
            t.name("failed_units")
                .description("Lists the systemd units currently in the failed state.")
                .executes(input -> report(runner.run(ARGV))));
  }

  static String report(CommandRunner.Output output) {
    if (!output.succeeded()) {
      return "systemctl failed: " + output.text().strip();
    }
    List<String> units =
        output.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    return units.isEmpty() ? "no failed units" : String.join("\n", units);
  }
}
