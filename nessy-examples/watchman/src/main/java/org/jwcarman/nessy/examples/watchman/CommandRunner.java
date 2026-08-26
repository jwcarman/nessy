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

/**
 * The one seam between every tool in this application and the host it watches (watchman spec §4:
 * "each tool takes a {@code CommandRunner} seam; the real one is {@code ProcessBuilder}").
 *
 * <p>It is an interface for exactly one reason: so that no test in this module ever shells out. A
 * tool test hands its tool a runner with canned {@code df} output and asserts on what the tool made
 * of it, which is the part worth testing — running a subprocess is the JDK's job, and {@link
 * ProcessRunner} is the only place that happens.
 */
public interface CommandRunner {

  /**
   * Runs {@code argv} to completion and reports what it did.
   *
   * @param argv the command and its arguments, already split — never a shell string, so nothing
   *     here can be quoted or interpolated into a shell
   * @return the exit code and both streams; a command that could not be started at all is an {@link
   *     Output} with a non-zero code and the failure on {@code stderr}, not an exception, because
   *     every caller is a tool whose answer to "that did not work" is a message for the model
   */
  Output run(List<String> argv);

  /**
   * The same, with a deadline of this call's own choosing rather than the runner's default.
   *
   * <p>Why the seam grew a second method (final review, finding #4): {@code
   * watchman.command-timeout} defaults to thirty seconds, which is right for {@code df} and
   * catastrophic for {@code apt-get -y upgrade}. {@link ProcessRunner} enforces a timeout with
   * {@code destroyForcibly()}, so a thirty-second budget on a package upgrade means <b>SIGKILL to
   * dpkg mid-transaction</b> — a half-configured package database, on a real server, that a human
   * then has to repair by hand. The one thing this agent must never do is break the box it was
   * watching.
   *
   * <p>This overload rather than a per-{@code Tool} timeout knob or a second runner bean, because
   * it is the smallest change that puts the decision where the argv is: {@link ApplyUpdates} knows
   * it is running an upgrade, and nothing else has to know anything. The default delegates, so
   * every existing implementation — including the tests' fake — keeps working unchanged.
   *
   * @param argv the command and its arguments
   * @param timeout how long THIS command may take
   */
  default Output run(List<String> argv, Duration timeout) {
    return run(argv);
  }

  /**
   * What one command did.
   *
   * @param exitCode the process's exit code, or a non-zero stand-in if it never started
   * @param stdout everything the process wrote to standard output
   * @param stderr everything the process wrote to standard error
   */
  record Output(int exitCode, String stdout, String stderr) {

    /** Whether the command reported success. */
    public boolean succeeded() {
      return exitCode == 0;
    }

    /** Standard output when the command succeeded, standard error when it did not. */
    public String text() {
      return succeeded() || !stdout.isBlank() ? stdout : stderr;
    }
  }
}
