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
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;

/** {@code restart_unit(name)} (spec §2.1): {@code systemctl restart <name>}, behind a human. */
public final class RestartUnit {

  /**
   * @param name the systemd unit to restart
   */
  public record Unit(String name) {}

  private RestartUnit() {}

  /** The literal command this grant renders and runs. */
  public static List<String> argv(Unit unit) {
    return List.of("systemctl", "restart", unit.name());
  }

  /** The tool: what runs once a human has said yes. */
  public static Tool<Unit> tool(CommandRunner runner) {
    return Remediation.tool(
        "restart_unit",
        "Restarts one systemd unit. Requires human approval; propose it, do not expect it to run"
            + " during this round.",
        Unit.class,
        RestartUnit::argv,
        runner);
  }

  /** The grant: deferred, with the exact command line as its action. */
  public static ToolGrant grant(CommandRunner runner) {
    return Remediation.grant(tool(runner), RestartUnit::argv);
  }
}
