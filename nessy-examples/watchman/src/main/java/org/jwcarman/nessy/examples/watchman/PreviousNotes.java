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
import org.jwcarman.nessy.api.tool.Tool;

/**
 * {@code previous_notes} (spec §2.1): the last few daily notes, so a round can tell "the disk has
 * been at 91% since Tuesday" from "the disk just filled up".
 */
public final class PreviousNotes {

  /**
   * @param count how many notes to read back; absent means the configured default
   */
  public record Lookback(Integer count) {}

  private PreviousNotes() {}

  /** The tool, over a notes directory and the default history depth. */
  public static Tool<Lookback> tool(Notes notes, int defaultCount) {
    Objects.requireNonNull(notes, "notes must not be null");
    if (defaultCount < 1) {
      throw new IllegalArgumentException("defaultCount must be at least 1");
    }
    return Tool.of(
        Lookback.class,
        t ->
            t.name("previous_notes")
                .description(
                    "Reads back the most recent daily notes, newest first. Use it at the start of a"
                        + " round to see what you already reported.")
                .executes(
                    lookback -> {
                      int count =
                          lookback.count() == null || lookback.count() < 1
                              ? defaultCount
                              : lookback.count();
                      List<String> recent = notes.recent(count);
                      return recent.isEmpty()
                          ? "no previous notes"
                          : String.join(System.lineSeparator(), recent);
                    }));
  }
}
