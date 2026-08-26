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
import org.jwcarman.nessy.api.tool.ToolGrant;

/**
 * {@code prune_images} (spec §2.1): {@code docker image prune -af}, behind a human.
 *
 * <p>{@code -a} is in the rendered line on purpose. It removes every unused image, not merely the
 * dangling ones, and the difference between those two is the difference between reclaiming a
 * gigabyte and re-pulling everything on the next deploy. The page shows the flag; the human decides
 * knowing it.
 */
public final class PruneImages {

  /** No input: prune is prune. */
  public record Prune() {}

  private PruneImages() {}

  /** The literal command this grant renders and runs. */
  public static List<String> argv(Prune prune) {
    return List.of("docker", "image", "prune", "-af");
  }

  /** The grant: deferred, with the exact command line as its action. */
  public static ToolGrant grant(CommandRunner runner) {
    return Remediation.grant(
        "prune_images",
        "Removes every unused Docker image to reclaim disk. Requires human approval; propose it,"
            + " do not expect it to run during this round.",
        Prune.class,
        PruneImages::argv,
        runner);
  }
}
