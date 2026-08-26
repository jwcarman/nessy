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
 * {@code journal_errors} (spec §2.1): error-priority journal lines from the recent past.
 *
 * <p>The window is an input rather than a constant, because a round that finds something wants to
 * look further back than the half hour a routine round asks for. The answer is truncated: a box
 * mid-incident can produce tens of thousands of error lines, and a tool result that large costs
 * real money in context and tells the model nothing the first hundred lines did not.
 */
public final class JournalErrors {

  /**
   * @param minutes how far back to look; absent means the last thirty minutes
   */
  public record Window(Integer minutes) {}

  private static final int MAX_LINES = 100;

  private JournalErrors() {}

  /** The tool, over the host seam. */
  public static Tool<Window> tool(CommandRunner runner) {
    Objects.requireNonNull(runner, "runner must not be null");
    return Tool.of(
        Window.class,
        t ->
            t.name("journal_errors")
                .description(
                    "Reads error-priority lines from the systemd journal over the last N minutes"
                        + " (30 by default).")
                .executes(window -> report(runner.run(argv(minutes(window))), minutes(window))));
  }

  static List<String> argv(int minutes) {
    return List.of(
        "journalctl", "-p", "err", "--since", "-" + minutes + "m", "--no-pager", "--no-hostname");
  }

  private static int minutes(Window window) {
    return window.minutes() == null || window.minutes() < 1 ? 30 : window.minutes();
  }

  static String report(CommandRunner.Output output, int minutes) {
    if (!output.succeeded()) {
      return "journalctl failed: " + output.text().strip();
    }
    List<String> lines =
        output.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    if (lines.isEmpty()) {
      return "no errors in the last " + minutes + " minutes";
    }
    if (lines.size() <= MAX_LINES) {
      return String.join("\n", lines);
    }
    return String.join("\n", lines.subList(0, MAX_LINES))
        + "\n… "
        + (lines.size() - MAX_LINES)
        + " more error lines in the last "
        + minutes
        + " minutes";
  }
}
