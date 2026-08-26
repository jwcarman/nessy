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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The notes directory: one markdown file per day, appended to, and read back.
 *
 * <p>This is the transcript a human reads (spec §3). It is deliberately not a database — after a
 * month of soaking, the thing James wants is a directory he can {@code cat}.
 */
public final class Notes {

  private final Path directory;
  private final Clock clock;

  /**
   * @param directory where the daily notes live; created on first write
   * @param clock which day "today" is
   */
  public Notes(Path directory, Clock clock) {
    this.directory = Objects.requireNonNull(directory, "directory must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** Today's note file, whether or not it exists yet. */
  public Path today() {
    return directory.resolve(LocalDate.now(clock) + ".md");
  }

  /**
   * Appends {@code text} to today's note as one bullet, creating the directory and the file if this
   * is the day's first note.
   *
   * @return the file the note landed in
   */
  public Path append(String text) {
    Objects.requireNonNull(text, "text must not be null");
    Path note = today();
    try {
      Files.createDirectories(directory);
      Files.writeString(
          note,
          "- " + text.strip() + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      return note;
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + note, e);
    }
  }

  /**
   * The {@code count} most recent notes, newest first, each as its whole file contents prefixed by
   * its date.
   */
  public List<String> recent(int count) {
    if (count < 1) {
      throw new IllegalArgumentException("count must be at least 1");
    }
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .filter(path -> path.getFileName().toString().endsWith(".md"))
          .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
          .limit(count)
          .map(Notes::read)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("could not list " + directory, e);
    }
  }

  private static String read(Path note) {
    try {
      return "## " + note.getFileName() + System.lineSeparator() + Files.readString(note);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + note, e);
    }
  }
}
