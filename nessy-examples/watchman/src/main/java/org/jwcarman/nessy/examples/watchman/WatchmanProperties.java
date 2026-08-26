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
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about the watchman that is site-specific: who may answer an approval, where the notes
 * live, and how long a command may take.
 *
 * <p>The round's cron expression is deliberately NOT here. {@code @Scheduled} reads {@code
 * ${watchman.cron:...}} straight from the environment, and a second copy in a record would be a
 * second place to get it wrong.
 *
 * @param user the single account the approval page accepts, from HTTP basic auth; it becomes the
 *     principal on every {@code ApprovalDesk} answer, so it ends up in the audit trail
 * @param password that account's password — required, with no default, because a LAN is not a trust
 *     boundary and a shipped default password is how a home server ends up on the internet
 * @param notesDir the directory the daily notes are written to and read back from
 * @param noteHistory how many previous notes {@code previous_notes} hands the model by default
 * @param commandTimeout how long any one host command may take before it is destroyed
 */
@ConfigurationProperties("watchman")
public record WatchmanProperties(
    String user, String password, Path notesDir, Integer noteHistory, Duration commandTimeout) {

  /**
   * Defaults for everything that has a sensible one, and a loud failure for the two that do not. A
   * blank credential fails the context at startup rather than opening the page to everyone.
   */
  public WatchmanProperties {
    require(user, "watchman.user");
    require(password, "watchman.password");
    notesDir = notesDir == null ? Path.of("notes") : notesDir;
    noteHistory = noteHistory == null ? 3 : noteHistory;
    commandTimeout = commandTimeout == null ? Duration.ofSeconds(30) : commandTimeout;
  }

  private static void require(String value, String key) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(key + " is required and has no default");
    }
  }
}
