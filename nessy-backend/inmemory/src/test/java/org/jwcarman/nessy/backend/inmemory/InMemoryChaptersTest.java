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
package org.jwcarman.nessy.backend.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.backend.chapter.Chapters;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Chapters held in this process")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InMemoryChaptersTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentType OTHER_TYPE = new AgentType("support");

  private final Chapters chapters =
      new InMemoryChapters(new JacksonCodecFactory(JsonMapper.builder().build()));
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
  class With_a_codec_that_marks_what_it_encodes {

    private final AtomicInteger encoded = new AtomicInteger();

    private CodecFactory counting() {
      JacksonCodecFactory jackson = new JacksonCodecFactory(JsonMapper.builder().build());
      return new CodecFactory() {
        @Override
        public <T> Codec<T> create(TypeRef<T> type) {
          Codec<T> inner = jackson.create(type);
          return new Codec<>() {
            @Override
            public byte[] encode(T value) {
              encoded.incrementAndGet();
              return inner.encode(value);
            }

            @Override
            public T decode(byte[] bytes) {
              return inner.decode(bytes);
            }
          };
        }
      };
    }

    @Test
    void a_summary_is_encoded_when_it_is_stored_and_reads_back_exactly() {
      Chapters counted = new InMemoryChapters(counting());
      counted.append(TYPE, agent, Optional.empty(), List.of(chapter(1, 3)));

      counted.summarize(new Summary(chapter(1, 3), "Ms. Okonkwo-Reyes, invoice 9087-1123"));

      assertThat(encoded.get()).isEqualTo(1);
      assertThat(counted.summaries(TYPE, agent))
          .containsExactly(new Summary(chapter(1, 3), "Ms. Okonkwo-Reyes, invoice 9087-1123"));
    }
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
      Optional<TurnId> after = Optional.of(turn(3));

      assertThatThrownBy(() -> chapters.append(TYPE, agent, after, toStore))
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
}
