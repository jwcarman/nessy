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

/**
 * {@code clean_journal(days)} (spec §2.1): {@code journalctl --vacuum-time=<days>d}, behind a
 * human.
 *
 * <p>The retention is part of the rendered line, so "vacuum to 7 days" and "vacuum to 1 day" are
 * visibly different questions on the page. A missing or nonsensical retention becomes 7 rather than
 * an error: the model asking to clean the journal has said the useful half, and 7 days is the
 * conservative reading of the other half.
 */
public final class CleanJournal {

  /**
   * @param days how much journal history to keep; absent means seven days
   */
  public record Retention(Integer days) {}

  private static final int DEFAULT_DAYS = 7;

  private CleanJournal() {}

  /** The literal command this grant renders and runs. */
  public static List<String> argv(Retention retention) {
    int days = retention.days() == null || retention.days() < 1 ? DEFAULT_DAYS : retention.days();
    return List.of("journalctl", "--vacuum-time=" + days + "d");
  }

  /** The tool: what runs once a human has said yes. */
  public static Tool<Retention> tool(CommandRunner runner) {
    return Remediation.tool(
        "clean_journal",
        "Vacuums the systemd journal down to the given number of days of history (7 by default)."
            + " Requires human approval; propose it, do not expect it to run during this round.",
        Retention.class,
        CleanJournal::argv,
        runner);
  }

  /** The grant: deferred, with the exact command line as its action. */
  public static ToolGrant grant(CommandRunner runner) {
    return Remediation.grant(tool(runner), CleanJournal::argv);
  }
}
