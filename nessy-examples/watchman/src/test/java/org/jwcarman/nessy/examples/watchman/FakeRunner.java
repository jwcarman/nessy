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
import java.util.Map;

/** Canned host output. No test in this module shells out. */
public final class FakeRunner implements CommandRunner {

  private static final Map<String, String> CANNED =
      Map.of(
          "df -h",
              """
              Filesystem Size Used Avail Use% Mounted on
              /dev/sda1  100G  91G    9G  91% /
              """,
          "docker ps -a --format json",
              "{\"Names\":\"grafana\",\"State\":\"running\",\"Status\":\"Up 2 days\"}\n"
                  + "{\"Names\":\"loki\",\"State\":\"exited\",\"Status\":\"Exited (1) 3h ago\"}",
          "docker image prune -af", "Total reclaimed space: 4.2GB",
          "fstrim -av", "/: 12 GiB trimmed");

  @Override
  public Output run(List<String> argv, Duration timeout) {
    String line = String.join(" ", argv);
    String out = CANNED.get(line);
    return out == null
        ? new Output(127, "", "no canned output for " + line)
        : new Output(0, out, "");
  }
}
