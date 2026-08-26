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

/** {@code restart_container(name)} (spec §2.1): {@code docker restart <name>}, behind a human. */
public final class RestartContainer {

  /**
   * @param name the container to restart
   */
  public record Container(String name) {}

  private RestartContainer() {}

  /**
   * The literal command this grant renders and runs.
   *
   * <p>{@code --} for the same reason {@link RestartUnit} has it: the container name comes from the
   * model, and an end-of-options marker is what stops a name beginning with a dash from being read
   * as a flag.
   */
  public static List<String> argv(Container container) {
    return List.of("docker", "restart", "--", container.name());
  }

  /** The tool: what runs once a human has said yes. */
  public static Tool<Container> tool(CommandRunner runner) {
    return Remediation.tool(
        "restart_container",
        "Restarts one Docker container. Requires human approval; propose it, do not expect it to"
            + " run during this round.",
        Container.class,
        RestartContainer::argv,
        runner);
  }

  /** The grant: deferred, with the exact command line as its action. */
  public static ToolGrant grant(CommandRunner runner) {
    return Remediation.grant(tool(runner), RestartContainer::argv);
  }
}
