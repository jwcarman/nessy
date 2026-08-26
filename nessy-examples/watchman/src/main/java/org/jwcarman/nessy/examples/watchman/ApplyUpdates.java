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
import org.jwcarman.nessy.api.tool.ToolGrant;

/** {@code apply_updates} (spec §2.1): the host's own upgrade command, behind a human. */
public final class ApplyUpdates {

  /** No input: the package manager decides what to upgrade. */
  public record Updates() {}

  private ApplyUpdates() {}

  /** The grant: deferred, with the exact command line as its action. */
  public static ToolGrant grant(CommandRunner runner, PackageManager manager) {
    Objects.requireNonNull(manager, "manager must not be null");
    List<String> argv = manager.upgrade();
    return Remediation.grant(
        "apply_updates",
        "Applies every pending package update. Requires human approval; propose it, do not expect"
            + " it to run during this round.",
        Updates.class,
        updates -> argv,
        runner);
  }
}
