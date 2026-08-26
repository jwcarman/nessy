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
import java.util.Locale;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * {@code updates_pending} (spec §2.1): what the host's package manager would upgrade.
 *
 * <p>Read-only and allowed — asking is safe. Applying is {@link ApplyUpdates}, and that one waits
 * for a human.
 */
public final class UpdatesPending {

  /** No input: everything upgradable. */
  public record Upgradable() {}

  private UpdatesPending() {}

  /** The tool, over the host seam and whichever package manager the host turned out to have. */
  public static Tool<Upgradable> tool(CommandRunner runner, PackageManager manager) {
    Objects.requireNonNull(runner, "runner must not be null");
    Objects.requireNonNull(manager, "manager must not be null");
    return Tool.of(
        Upgradable.class,
        t ->
            t.name("updates_pending")
                .description("Lists the packages the host's package manager has updates for.")
                .executes(input -> report(runner.run(manager.check()), manager)));
  }

  static String report(CommandRunner.Output output, PackageManager manager) {
    if (!manager.checkRan(output.exitCode())) {
      return manager.name().toLowerCase(Locale.ROOT) + " failed: " + output.text().strip();
    }
    List<String> lines =
        output
            .stdout()
            .lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.equals("Listing..."))
            .toList();
    return lines.isEmpty() ? "no updates pending" : String.join("\n", lines);
  }
}
