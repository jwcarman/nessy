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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Feature detection (watchman spec §2.1): "a {@code which} at startup; an absent tool is not
 * registered, so the model never sees it."
 *
 * <p>The result is published as ordinary {@code watchman.detected.*} properties before the context
 * is built, and every tool bean is gated on the one it needs. That indirection buys two things
 * worth having: the detection runs exactly once per process rather than once per bean, and a test
 * states the host it is pretending to be by setting properties instead of by shelling out — which
 * is the promise spec §4 makes.
 */
public final class Detect {

  /** The property prefix each answer is published under. */
  public static final String PREFIX = "watchman.detected.";

  /**
   * Every command any tool in this application runs. A name that is not here can never gate a bean,
   * so this list and {@link ToolBeans} are read together.
   */
  public static final List<String> COMMANDS =
      List.of("df", "systemctl", "journalctl", "docker", "apt", "dnf", "fstrim");

  /**
   * The one detected feature that is not a command: whether {@code /proc/loadavg} and {@code
   * /proc/uptime} can be read. {@code uptime_load} is gated on this.
   */
  public static final String PROC = PREFIX + "proc";

  private final CommandRunner runner;
  private final Path procDir;

  /** Detection against the real {@code /proc}. */
  public Detect(CommandRunner runner) {
    this(runner, Path.of("/proc"));
  }

  /**
   * @param runner the seam {@code which} itself runs through — the same one the tools use
   * @param procDir the {@code /proc} filesystem, or a stand-in
   */
  public Detect(CommandRunner runner, Path procDir) {
    this.runner = Objects.requireNonNull(runner, "runner must not be null");
    this.procDir = Objects.requireNonNull(procDir, "procDir must not be null");
  }

  /** Whether {@code which command} says the host has it. */
  public boolean present(String command) {
    Objects.requireNonNull(command, "command must not be null");
    return runner.run(List.of("which", command)).succeeded();
  }

  /**
   * Every command in {@link #COMMANDS}, as {@code watchman.detected.<name>} → {@code "true"} or
   * {@code "false"}. Both answers are published, never just the present ones: a bean gated on a
   * property that is simply missing cannot tell "this host has no docker" from "detection never
   * ran", and those deserve different outcomes.
   */
  public Map<String, Object> asProperties() {
    Map<String, Object> detected = new LinkedHashMap<>();
    for (String command : COMMANDS) {
      detected.put(PREFIX + command, Boolean.toString(present(command)));
    }
    detected.put(PROC, Boolean.toString(new UptimeLoad(procDir).available()));
    return Map.copyOf(detected);
  }
}
