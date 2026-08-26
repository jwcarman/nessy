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

import java.util.Objects;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * {@code write_note} (spec §2.1): the tool every round ends with. Allowed outright — writing to the
 * agent's own notebook is not an action anyone needs to approve.
 */
public final class WriteNote {

  /**
   * @param text one round's finding, in the model's own words
   */
  public record Note(String text) {}

  private WriteNote() {}

  /** The tool, over a notes directory. */
  public static Tool<Note> tool(Notes notes) {
    Objects.requireNonNull(notes, "notes must not be null");
    return Tool.of(
        Note.class,
        t ->
            t.name("write_note")
                .description(
                    "Appends one line to today's note in the watchman's notes directory. End every"
                        + " round with a note saying what you found and what you did or proposed.")
                .executes(note -> "noted in " + notes.append(note.text())));
  }
}
