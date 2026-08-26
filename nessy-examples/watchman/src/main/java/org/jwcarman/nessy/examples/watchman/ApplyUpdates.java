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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;

/** {@code apply_updates} (spec §2.1): the host's own upgrade command, behind a human. */
public final class ApplyUpdates {

  /** No input: the package manager decides what to upgrade. */
  public record Updates() {}

  private ApplyUpdates() {}

  /** The literal command this grant renders and runs, on this host's package manager. */
  public static Function<Updates, List<String>> argv(PackageManager manager) {
    Objects.requireNonNull(manager, "manager must not be null");
    List<String> upgrade = manager.upgrade();
    return updates -> upgrade;
  }

  /**
   * The tool: what runs once a human has said yes.
   *
   * @param timeout how long the upgrade may take — {@code watchman.upgrade-timeout}, fifteen
   *     minutes by default, NOT the thirty-second {@code watchman.command-timeout} every other
   *     command gets. The timeout is enforced by destroying the process, so too small a budget here
   *     means SIGKILL to dpkg mid-transaction and a package database a human has to repair by hand.
   */
  public static Tool<Updates> tool(CommandRunner runner, PackageManager manager, Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    return Remediation.tool(
        "apply_updates",
        "Applies every pending package update. Requires human approval; propose it, do not expect"
            + " it to run during this round.",
        Updates.class,
        argv(manager),
        runner,
        timeout);
  }

  /** The grant: deferred, with the exact command line as its action. */
  public static ToolGrant grant(CommandRunner runner, PackageManager manager, Duration timeout) {
    return Remediation.grant(tool(runner, manager, timeout), argv(manager));
  }
}
