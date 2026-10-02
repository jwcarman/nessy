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
package org.jwcarman.nessy.backend.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("container")
@DisplayName("Chapters kept in a database")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JdbcChaptersTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentType OTHER_TYPE = new AgentType("support");

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final DataSource database = database();
  private final JdbcClient jdbc = JdbcClient.create(database);
  private final Chapters chapters = new JdbcChapters(database);

  /** An agent nobody else in the shared database is using. */
  private final AgentId agent = AgentId.random();

  private static TurnId turn(long value) {
    return new TurnId(value);
  }

  private Chapter chapter(long from, long through) {
    return new Chapter(TYPE, agent, turn(from), turn(through));
  }

  private boolean appendAfter(Optional<TurnId> after, Chapter... toStore) {
    return chapters.append(TYPE, agent, after, List.of(toStore));
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Appending {

    @Test
    void stores_chapters_for_an_agent_with_none() {
      boolean stored = appendAfter(Optional.empty(), chapter(1, 3), chapter(5, 6));

      assertThat(stored).isTrue();
      assertThat(chapters.unsummarized(TYPE, agent)).containsExactly(chapter(1, 3), chapter(5, 6));
      assertThat(chapters.closedThrough(TYPE, agent)).contains(turn(6));
    }

    @Test
    void stores_after_the_current_end() {
      appendAfter(Optional.empty(), chapter(1, 3));

      boolean stored = appendAfter(Optional.of(turn(3)), chapter(4, 7));

      assertThat(stored).isTrue();
      assertThat(chapters.unsummarized(TYPE, agent)).containsExactly(chapter(1, 3), chapter(4, 7));
    }

    @Test
    void returns_false_and_stores_nothing_when_after_is_stale() {
      appendAfter(Optional.empty(), chapter(1, 3), chapter(4, 7));

      boolean stored = appendAfter(Optional.of(turn(3)), chapter(8, 9));

      assertThat(stored).isFalse();
      assertThat(chapters.closedThrough(TYPE, agent)).contains(turn(7));
      assertThat(chapters.unsummarized(TYPE, agent)).hasSize(2);
    }

    @Test
    void returns_false_when_an_agent_with_chapters_is_appended_to_with_after_empty() {
      appendAfter(Optional.empty(), chapter(1, 3));

      boolean stored = appendAfter(Optional.empty(), chapter(4, 5));

      assertThat(stored).isFalse();
      assertThat(chapters.unsummarized(TYPE, agent)).containsExactly(chapter(1, 3));
    }

    @Test
    void returns_false_when_after_names_a_turn_but_the_agent_has_no_chapters() {
      boolean stored = appendAfter(Optional.of(turn(3)), chapter(4, 5));

      assertThat(stored).isFalse();
      assertThat(chapters.closedThrough(TYPE, agent)).isEmpty();
    }

    @Test
    void an_empty_list_returns_true_and_stores_nothing() {
      boolean stored = chapters.append(TYPE, agent, Optional.empty(), List.of());

      assertThat(stored).isTrue();
      assertThat(chapters.closedThrough(TYPE, agent)).isEmpty();
    }

    @Test
    void rejects_a_chapter_for_another_agent() {
      Chapter foreign = new Chapter(TYPE, AgentId.random(), turn(1), turn(2));
      List<Chapter> toStore = List.of(foreign);

      assertThatThrownBy(() -> chapters.append(TYPE, agent, Optional.empty(), toStore))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_chapter_for_the_same_id_under_another_type() {
      Chapter foreign = new Chapter(OTHER_TYPE, agent, turn(1), turn(2));
      List<Chapter> toStore = List.of(foreign);

      assertThatThrownBy(() -> chapters.append(TYPE, agent, Optional.empty(), toStore))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_overlapping_chapters() {
      List<Chapter> toStore = List.of(chapter(1, 5), chapter(5, 8));

      assertThatThrownBy(() -> chapters.append(TYPE, agent, Optional.empty(), toStore))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_unordered_chapters() {
      List<Chapter> toStore = List.of(chapter(6, 8), chapter(1, 3));

      assertThatThrownBy(() -> chapters.append(TYPE, agent, Optional.empty(), toStore))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_first_chapter_that_does_not_come_after_after() {
      List<Chapter> toStore = List.of(chapter(3, 5));

      assertThatThrownBy(() -> chapters.append(TYPE, agent, Optional.of(turn(3)), toStore))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Summarising {

    @Test
    void stores_text_for_a_chapter_with_none() {
      appendAfter(Optional.empty(), chapter(1, 3));

      boolean stored = chapters.summarize(new Summary(chapter(1, 3), "they said hello"));

      assertThat(stored).isTrue();
      assertThat(chapters.unsummarized(TYPE, agent)).isEmpty();
      assertThat(chapters.summaries(TYPE, agent))
          .containsExactly(new Summary(chapter(1, 3), "they said hello"));
    }

    @Test
    void returns_false_the_second_time_and_keeps_the_first_text() {
      appendAfter(Optional.empty(), chapter(1, 3));
      chapters.summarize(new Summary(chapter(1, 3), "first"));

      boolean stored = chapters.summarize(new Summary(chapter(1, 3), "second"));

      assertThat(stored).isFalse();
      assertThat(chapters.summaries(TYPE, agent))
          .containsExactly(new Summary(chapter(1, 3), "first"));
    }

    @Test
    void returns_false_for_a_chapter_that_was_never_closed() {
      appendAfter(Optional.empty(), chapter(1, 3));

      boolean stored = chapters.summarize(new Summary(chapter(4, 6), "unclosed"));

      assertThat(stored).isFalse();
      assertThat(chapters.summaries(TYPE, agent)).isEmpty();
    }

    @Test
    void returns_false_for_bounds_that_differ_from_the_closed_chapter() {
      appendAfter(Optional.empty(), chapter(1, 3));

      boolean stored = chapters.summarize(new Summary(chapter(1, 2), "wrong bounds"));

      assertThat(stored).isFalse();
      assertThat(chapters.unsummarized(TYPE, agent)).containsExactly(chapter(1, 3));
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Reading {

    @Test
    void closed_through_is_empty_for_a_new_agent() {
      assertThat(chapters.closedThrough(TYPE, agent)).isEmpty();
    }

    @Test
    void closed_through_is_the_last_turn_of_the_last_chapter() {
      appendAfter(Optional.empty(), chapter(1, 3), chapter(4, 9));

      assertThat(chapters.closedThrough(TYPE, agent)).contains(turn(9));
    }

    @Test
    void unsummarized_returns_closed_chapters_without_text_oldest_first() {
      appendAfter(Optional.empty(), chapter(1, 3), chapter(4, 6), chapter(7, 9));
      chapters.summarize(new Summary(chapter(4, 6), "middle"));

      assertThat(chapters.unsummarized(TYPE, agent)).containsExactly(chapter(1, 3), chapter(7, 9));
    }

    @Test
    void summaries_returns_the_unbroken_run_from_the_first_chapter() {
      appendAfter(Optional.empty(), chapter(1, 3), chapter(4, 6), chapter(7, 9));
      chapters.summarize(new Summary(chapter(1, 3), "one"));
      chapters.summarize(new Summary(chapter(4, 6), "two"));

      assertThat(chapters.summaries(TYPE, agent))
          .containsExactly(new Summary(chapter(1, 3), "one"), new Summary(chapter(4, 6), "two"));
    }

    @Test
    void summaries_stops_at_the_first_chapter_with_no_text_even_when_a_later_one_has_text() {
      appendAfter(Optional.empty(), chapter(1, 3), chapter(4, 6), chapter(7, 9));
      chapters.summarize(new Summary(chapter(1, 3), "one"));
      chapters.summarize(new Summary(chapter(7, 9), "three"));

      assertThat(chapters.summaries(TYPE, agent))
          .containsExactly(new Summary(chapter(1, 3), "one"));
    }

    @Test
    void summaries_is_empty_when_the_first_chapter_has_no_text() {
      appendAfter(Optional.empty(), chapter(1, 3), chapter(4, 6));
      chapters.summarize(new Summary(chapter(4, 6), "two"));

      assertThat(chapters.summaries(TYPE, agent)).isEmpty();
    }

    @Test
    void one_agents_chapters_are_invisible_to_another_agent() {
      appendAfter(Optional.empty(), chapter(1, 3));
      AgentId stranger = AgentId.random();

      assertThat(chapters.closedThrough(TYPE, stranger)).isEmpty();
      assertThat(chapters.unsummarized(TYPE, stranger)).isEmpty();
      assertThat(chapters.summaries(TYPE, stranger)).isEmpty();
    }

    @Test
    void and_to_the_same_id_under_another_type() {
      appendAfter(Optional.empty(), chapter(1, 3));
      chapters.summarize(new Summary(chapter(1, 3), "one"));

      assertThat(chapters.closedThrough(OTHER_TYPE, agent)).isEmpty();
      assertThat(chapters.unsummarized(OTHER_TYPE, agent)).isEmpty();
      assertThat(chapters.summaries(OTHER_TYPE, agent)).isEmpty();
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Racing {

    private static final int CALLERS = 8;

    @Test
    void of_many_appending_different_chapters_after_the_same_point_exactly_one_is_stored()
        throws Exception {
      appendAfter(Optional.empty(), chapter(1, 3));
      try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
          Chapter mine = chapter(4 + i, 4 + i);
          outcomes.add(
              callers.submit(
                  () -> {
                    go.await();
                    return appendAfter(Optional.of(turn(3)), mine);
                  }));
        }
        go.countDown();

        int stored = 0;
        for (Future<Boolean> outcome : outcomes) {
          if (outcome.get()) {
            stored++;
          }
        }

        assertThat(stored).isEqualTo(1);
      }
      assertThat(rowsFor(agent)).isEqualTo(2);
    }

    @Test
    void of_many_appending_to_an_agent_with_none_exactly_one_is_stored() throws Exception {
      try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
          Chapter mine = chapter(1 + i, 1 + i);
          outcomes.add(
              callers.submit(
                  () -> {
                    go.await();
                    return appendAfter(Optional.empty(), mine);
                  }));
        }
        go.countDown();

        int stored = 0;
        for (Future<Boolean> outcome : outcomes) {
          if (outcome.get()) {
            stored++;
          }
        }

        assertThat(stored).isEqualTo(1);
      }
      assertThat(rowsFor(agent)).isEqualTo(1);
    }

    @Test
    void of_many_cutting_the_same_turns_differently_one_callers_chapters_are_stored_whole()
        throws Exception {
      List<List<Chapter>> cuts = new ArrayList<>();
      for (int i = 0; i < CALLERS; i++) {
        // All start at turn 1 or 2, so some share a first from_turn; each cuts 1..12 its own way.
        long start = 1 + (i % 2);
        long first = start + 1 + (i % 3);
        long second = first + 2 + (i % 4);
        cuts.add(
            i % 2 == 0
                ? List.of(
                    chapter(start, first), chapter(first + 1, second), chapter(second + 1, 20))
                : List.of(chapter(start, first), chapter(first + 1, 20)));
      }
      List<Future<Boolean>> outcomes = new ArrayList<>();
      try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
        CountDownLatch go = new CountDownLatch(1);
        for (List<Chapter> cut : cuts) {
          outcomes.add(
              callers.submit(
                  () -> {
                    go.await();
                    return chapters.append(TYPE, agent, Optional.empty(), cut);
                  }));
        }
        go.countDown();
      }

      int winner = -1;
      int stored = 0;
      for (int i = 0; i < outcomes.size(); i++) {
        if (outcomes.get(i).get()) {
          stored++;
          winner = i;
        }
      }

      assertThat(stored).isEqualTo(1);
      assertThat(chapters.unsummarized(TYPE, agent)).isEqualTo(cuts.get(winner));
    }

    @Test
    void a_call_that_loses_stores_none_of_its_chapters() {
      appendAfter(Optional.empty(), chapter(1, 3));

      boolean stored = appendAfter(Optional.of(turn(2)), chapter(4, 5), chapter(6, 7));

      assertThat(stored).isFalse();
      assertThat(rowsFor(agent)).isEqualTo(1);
    }
  }

  private int rowsFor(AgentId id) {
    return jdbc.sql("SELECT count(*) FROM nessy_chapter WHERE agent_type = ? AND agent_id = ?")
        .params(TYPE.value(), id.value())
        .query(Integer.class)
        .single();
  }
}
