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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.time.Duration;
import java.util.List;

/**
 * The one seam between every tool and the host. Copied unchanged in spirit from the sibling
 * watchman: an interface so that no test in this module ever shells out.
 */
public interface CommandRunner {

  Output run(List<String> argv, Duration timeout);

  record Output(int exitCode, String stdout, String stderr) {
    public boolean succeeded() {
      return exitCode == 0;
    }

    public String text() {
      return succeeded() || !stdout.isBlank() ? stdout : stderr;
    }
  }
}
