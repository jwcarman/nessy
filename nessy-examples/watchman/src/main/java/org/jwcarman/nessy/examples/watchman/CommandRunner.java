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
