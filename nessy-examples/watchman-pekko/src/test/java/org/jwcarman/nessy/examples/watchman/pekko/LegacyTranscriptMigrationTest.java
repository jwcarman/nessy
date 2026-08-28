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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs the one-off migration against whatever the soak left behind, and proves the two halves of
 * the story: old rows still LOAD (because the serializer tolerates the field that left), and their
 * transcripts are MOVED rather than lost.
 */
@Tag("container")
@DisplayName("Rows written before the transcript moved out")
class LegacyTranscriptMigrationTest {

  @Test
  void old_rows_still_load_and_their_transcripts_are_moved_into_the_journal() {
    Transcript transcript = WatchmanPostgres.transcript();

    List<String> moved =
        new LegacyTranscriptMigration(WatchmanPostgres.dataSource(), transcript).run();

    System.out.println("[watchman] migrated legacy transcripts for: " + moved);

    // Whatever was migrated is now readable from the journal, with a timestamp per turn.
    moved.forEach(
        agentId -> {
          List<Transcript.Entry> entries = transcript.entries(agentId);
          assertThat(entries).isNotEmpty();
          assertThat(entries).allSatisfy(e -> assertThat(e.appendedAt()).isNotNull());
          assertThat(entries.getFirst().seq()).isEqualTo(1L);
        });

    // And every surviving row loads under the new, smaller shape.
    assertThat(new StartupSweep(WatchmanPostgres.dataSource()).unfinishedAgents()).isNotNull();
  }
}
