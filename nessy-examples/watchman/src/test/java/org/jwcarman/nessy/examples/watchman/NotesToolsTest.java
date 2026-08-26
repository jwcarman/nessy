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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The notes directory — the transcript a human reads (spec §3), and the two tools over it. */
class NotesToolsTest {

  private static Clock on(String date) {
    return Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC);
  }

  @Nested
  class Write_note {

    @Test
    void appends_to_todays_note_and_says_where_it_landed(@TempDir Path dir) throws IOException {
      Notes notes = new Notes(dir, on("2026-08-26"));

      String answer = Tools.content(WriteNote.tool(notes), new WriteNote.Note("disk at 90%"));

      Path today = dir.resolve("2026-08-26.md");
      assertThat(answer).contains(today.toString());
      assertThat(Files.readString(today)).isEqualTo("- disk at 90%" + System.lineSeparator());
    }

    @Test
    void adds_a_second_round_to_the_same_day(@TempDir Path dir) throws IOException {
      Notes notes = new Notes(dir, on("2026-08-26"));

      Tools.content(WriteNote.tool(notes), new WriteNote.Note("first"));
      Tools.content(WriteNote.tool(notes), new WriteNote.Note("second"));

      assertThat(Files.readString(dir.resolve("2026-08-26.md")))
          .contains("- first")
          .contains("- second");
    }

    @Test
    void creates_the_directory_on_the_very_first_round(@TempDir Path parent) {
      Path dir = parent.resolve("not-yet-there");
      Notes notes = new Notes(dir, on("2026-08-26"));

      Tools.content(WriteNote.tool(notes), new WriteNote.Note("hello"));

      assertThat(dir.resolve("2026-08-26.md")).exists();
    }
  }

  @Nested
  class Previous_notes {

    @Test
    void hands_back_the_most_recent_notes_newest_first(@TempDir Path dir) {
      new Notes(dir, on("2026-08-24")).append("monday");
      new Notes(dir, on("2026-08-25")).append("tuesday");
      new Notes(dir, on("2026-08-26")).append("wednesday");

      String answer =
          Tools.content(
              PreviousNotes.tool(new Notes(dir, on("2026-08-26")), 3),
              new PreviousNotes.Lookback(2));

      assertThat(answer).contains("wednesday").contains("tuesday").doesNotContain("monday");
      assertThat(answer.indexOf("wednesday")).isLessThan(answer.indexOf("tuesday"));
    }

    @Test
    void falls_back_to_the_configured_depth_when_the_model_does_not_say(@TempDir Path dir) {
      new Notes(dir, on("2026-08-24")).append("monday");
      new Notes(dir, on("2026-08-25")).append("tuesday");

      String answer =
          Tools.content(
              PreviousNotes.tool(new Notes(dir, on("2026-08-26")), 1),
              new PreviousNotes.Lookback(null));

      assertThat(answer).contains("tuesday").doesNotContain("monday");
    }

    @Test
    void says_so_on_the_very_first_round(@TempDir Path parent) {
      Path dir = parent.resolve("empty");

      String answer =
          Tools.content(
              PreviousNotes.tool(new Notes(dir, on("2026-08-26")), 3),
              new PreviousNotes.Lookback(null));

      assertThat(answer).isEqualTo("no previous notes");
    }
  }
}
