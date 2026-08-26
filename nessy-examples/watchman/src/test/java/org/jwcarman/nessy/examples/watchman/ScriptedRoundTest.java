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
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The five-minute promise, Boot edition: {@code --scripted} runs a whole round with no API key, no
 * network and no database, and the round ends the way the system prompt says every round ends — a
 * note on disk.
 *
 * <p>Scheduling is off, so the round happens because this test asked for it rather than because a
 * cron expression fired mid-assertion.
 */
@SpringBootTest(
    classes = WatchmanApplication.class,
    properties = {
      // No database here: this is the five-minute path, so the stores are the in-memory pair the
      // starter falls back to. The durable half is proved over a real Postgres in RoundTest.
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "watchman.scheduling.enabled=false",
      "watchman.user=ops",
      "watchman.password=lan-only",
      "watchman.notes-dir=${java.io.tmpdir}/watchman-scripted-round"
    })
@ActiveProfiles("scripted")
class ScriptedRoundTest {

  @Autowired private Rounds rounds;

  @Autowired private WatchmanProperties properties;

  @Test
  void a_scripted_round_ends_with_a_note_on_disk() throws IOException {
    clean(properties.notesDir());

    rounds.doRounds();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(notes(properties.notesDir())).isNotEmpty());
    assertThat(notes(properties.notesDir()))
        .singleElement()
        .satisfies(note -> assertThat(read(note)).contains("nothing on fire"));
  }

  private static List<Path> notes(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.sorted().toList();
    }
  }

  private static String read(Path note) {
    try {
      return Files.readString(note);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + note, e);
    }
  }

  private static void clean(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> entries = Files.walk(dir)) {
      for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
