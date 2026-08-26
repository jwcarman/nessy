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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * {@code uptime_load} (spec §2.1): {@code /proc/loadavg} and {@code /proc/uptime}.
 *
 * <p>The only read-only tool that runs no command at all — these are two files, and reading them
 * directly is both cheaper and more honest than parsing {@code uptime}'s prose, which changes shape
 * depending on how long the box has been up. The {@code /proc} directory is a parameter so a test
 * can hand it a temporary directory containing the same two files, which is how this module keeps
 * its promise that no test touches the host.
 */
public final class UptimeLoad {

  /** No input: how loaded, how long up. */
  public record Health() {}

  private final Path procDir;

  /**
   * @param procDir the {@code /proc} filesystem, or a stand-in
   */
  public UptimeLoad(Path procDir) {
    this.procDir = Objects.requireNonNull(procDir, "procDir must not be null");
  }

  /** Whether this host publishes the two files at all — the detection this tool needs. */
  public boolean available() {
    return Files.isReadable(procDir.resolve("loadavg"))
        && Files.isReadable(procDir.resolve("uptime"));
  }

  /** The tool. */
  public Tool<Health> tool() {
    return Tool.of(
        Health.class,
        t ->
            t.name("uptime_load")
                .description(
                    "Reports the 1/5/15-minute load averages and how long the host has been up.")
                .executes(input -> report()));
  }

  String report() {
    String loadavg = read("loadavg");
    String uptime = read("uptime");
    if (loadavg == null || uptime == null) {
      return "/proc is not readable on this host";
    }
    String[] load = loadavg.strip().split("\\s+");
    if (load.length < 3) {
      return "unparseable /proc/loadavg: " + loadavg.strip();
    }
    return "load "
        + load[0]
        + " "
        + load[1]
        + " "
        + load[2]
        + ", up "
        + humanize(uptime)
        + " (raw loadavg: "
        + loadavg.strip()
        + ")";
  }

  private static String humanize(String uptime) {
    String[] fields = uptime.strip().split("\\s+");
    try {
      Duration up = Duration.ofSeconds((long) Double.parseDouble(fields[0]));
      return up.toDays() + "d " + up.toHoursPart() + "h " + up.toMinutesPart() + "m";
    } catch (NumberFormatException e) {
      return "unparseable (" + uptime.strip() + ")";
    }
  }

  private String read(String name) {
    Path file = procDir.resolve(name);
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return null;
    }
  }
}
