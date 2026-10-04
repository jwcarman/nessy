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
package org.jwcarman.nessy.engine.chapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.inmemory.InMemoryChapters;
import org.jwcarman.nessy.backend.inmemory.InMemoryLeases;
import org.jwcarman.nessy.backend.lease.Attempt;
import org.jwcarman.nessy.backend.lease.LeaseKind;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ChapterKeeperTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Duration TTL = Duration.ofMinutes(1);

  private final Chapters chapters =
      new InMemoryChapters(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final List<Long> completed = new CopyOnWriteArrayList<>();

  private static TurnId id(long value) {
    return new TurnId(value);
  }

  private static Chapter chapter(long from, long through) {
    return new Chapter(TYPE, AGENT, id(from), id(through));
  }

  private static List<TurnId> ids(long... values) {
    return LongStream.of(values).mapToObj(ChapterKeeperTest::id).toList();
  }

  private void completeTurns(long... values) {
    for (long value : values) {
      completed.add(value);
    }
  }

  private TurnHistories histories() {
    return (type, agent) ->
        new TurnHistory() {
          @Override
          public List<TurnId> completedAfter(Optional<TurnId> through) {
            return completed.stream()
                .filter(turn -> through.isEmpty() || turn > through.get().value())
                .map(ChapterKeeperTest::id)
                .toList();
          }

          @Override
          public List<Turn> lastTurns(int turns) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<Turn> turnsFrom(long fromTurn) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<Turn> lastTurnsAfter(TurnId through, int turns) {
            throw new UnsupportedOperationException();
          }

          @Override
          public long turnsAfter(long through) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<Turn> turnsBetween(TurnId from, TurnId through) {
            throw new UnsupportedOperationException();
          }
        };
  }

  private ChapterKeeper keeper(ChapterPolicy policy, Summarizer summarizer, int max) {
    return keeper(policy, summarizer, new InMemoryLeases(), max);
  }

  private ChapterKeeper keeper(
      ChapterPolicy policy, Summarizer summarizer, Leases leases, int max) {
    return new ChapterKeeper(TYPE, policy, summarizer, chapters, leases, histories(), max, TTL);
  }

  private static final Summarizer SAYS_SOMETHING = chapter -> "about " + chapter.through().value();

  private List<Chapter> unsummarized() {
    return chapters.unsummarized(TYPE, AGENT);
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Cutting {

    private final Summarizer unwritten = chapter -> "";

    @Test
    void nothing_is_cut_while_the_policy_returns_nothing_and_fewer_than_the_maximum_are_open() {
      completeTurns(1, 2, 3);

      keeper(open -> List.of(), unwritten, 4).keep(AGENT);

      assertThat(unsummarized()).isEmpty();
      assertThat(chapters.closedThrough(TYPE, AGENT)).isEmpty();
    }

    @Test
    void the_policys_answer_becomes_a_chapter_over_the_turns_up_to_it() {
      completeTurns(1, 2, 3, 4);

      keeper(open -> ids(3), unwritten, 10).keep(AGENT);

      assertThat(unsummarized()).containsExactly(chapter(1, 3));
    }

    @Test
    void non_consecutive_turn_ids_make_chapters_with_the_ids_of_the_turns_that_exist() {
      completeTurns(10, 25, 40, 90);

      keeper(open -> ids(40), unwritten, 10).keep(AGENT);

      assertThat(unsummarized()).containsExactly(chapter(10, 40));
    }

    @Test
    void two_ends_in_one_answer_make_two_chapters_each_starting_after_the_last() {
      completeTurns(1, 2, 3, 4, 5);

      keeper(open -> ids(2, 4), unwritten, 10).keep(AGENT);

      assertThat(unsummarized()).containsExactly(chapter(1, 2), chapter(3, 4));
    }

    @Test
    void an_answer_behind_the_newest_turn_leaves_the_newer_turns_open() {
      completeTurns(1, 2, 3, 4);
      ChapterKeeper keeper = keeper(open -> ids(2), unwritten, 10);

      keeper.keep(AGENT);

      assertThat(chapters.closedThrough(TYPE, AGENT)).contains(id(2));
    }

    @Test
    void the_second_call_cuts_from_where_the_first_ended() {
      completeTurns(1, 2);
      List<List<TurnId>> seen = new ArrayList<>();
      ChapterKeeper keeper =
          keeper(
              open -> {
                seen.add(open.turns());
                return List.of(open.turns().getLast());
              },
              unwritten,
              10);
      keeper.keep(AGENT);
      completeTurns(3, 4);

      keeper.keep(AGENT);

      assertThat(seen).containsExactly(ids(1, 2), ids(3, 4));
      assertThat(unsummarized()).containsExactly(chapter(1, 2), chapter(3, 4));
    }

    static Stream<Arguments> answersThatAreNotTheOpenTurnsInOrder() {
      return Stream.of(
          arguments("an answer naming a turn that is not open cuts nothing", ids(2, 99)),
          arguments("an unordered answer cuts nothing", ids(3, 2)),
          arguments("a repeated turn in the answer cuts nothing", ids(2, 2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answersThatAreNotTheOpenTurnsInOrder")
    void an_answer_that_is_not_the_open_turns_in_order_cuts_nothing(
        String description, List<TurnId> answer) {
      completeTurns(1, 2, 3);

      keeper(open -> answer, unwritten, 10).keep(AGENT);

      assertThat(unsummarized()).as(description).isEmpty();
    }

    @Test
    void a_policy_that_throws_cuts_nothing() {
      completeTurns(1, 2, 3);
      ChapterPolicy throwing =
          open -> {
            throw new IllegalStateException("the policy broke");
          };

      keeper(throwing, unwritten, 10).keep(AGENT);

      assertThat(unsummarized()).isEmpty();
      assertThat(chapters.closedThrough(TYPE, AGENT)).isEmpty();
    }

    @Test
    void the_maximum_forces_a_chapter_over_the_oldest_turns_when_the_policy_returns_nothing() {
      completeTurns(1, 2, 3, 4, 5);

      keeper(open -> List.of(), unwritten, 3).keep(AGENT);

      assertThat(unsummarized()).containsExactly(chapter(1, 3));
    }

    @Test
    void a_chapter_longer_than_the_maximum_is_split_into_consecutive_chapters() {
      completeTurns(1, 2, 3, 4, 5, 6, 7);

      keeper(open -> ids(7), unwritten, 3).keep(AGENT);

      assertThat(unsummarized()).containsExactly(chapter(1, 3), chapter(4, 6), chapter(7, 7));
    }

    @Test
    void a_policy_that_throws_at_the_maximum_still_closes_a_chapter_over_the_oldest_turns() {
      completeTurns(1, 2, 3, 4);
      ChapterPolicy throwing =
          open -> {
            throw new IllegalStateException("the policy broke");
          };

      keeper(throwing, unwritten, 3).keep(AGENT);

      assertThat(unsummarized()).containsExactly(chapter(1, 3));
    }

    @Test
    void an_invalid_answer_at_the_maximum_still_closes_a_chapter_over_the_oldest_turns() {
      completeTurns(1, 2, 3, 4);

      keeper(open -> ids(99), unwritten, 3).keep(AGENT);

      assertThat(unsummarized()).containsExactly(chapter(1, 3));
    }

    @Test
    void a_null_answer_cuts_nothing_below_the_maximum_and_summarising_still_runs() {
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(chapter(1, 2)))).isTrue();
      completeTurns(1, 2, 3);

      keeper(open -> null, SAYS_SOMETHING, 10).keep(AGENT);

      assertThat(chapters.closedThrough(TYPE, AGENT)).contains(id(2));
      assertThat(chapters.summaries(TYPE, AGENT)).hasSize(1);
    }

    @Test
    void an_answer_holding_a_null_cuts_nothing_below_the_maximum_and_summarising_still_runs() {
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(chapter(1, 2)))).isTrue();
      completeTurns(1, 2, 3);
      List<TurnId> holdingNull = new ArrayList<>();
      holdingNull.add(null);

      keeper(open -> holdingNull, SAYS_SOMETHING, 10).keep(AGENT);

      assertThat(chapters.closedThrough(TYPE, AGENT)).contains(id(2));
      assertThat(chapters.summaries(TYPE, AGENT)).hasSize(1);
    }

    @Test
    void a_null_answer_at_the_maximum_still_closes_a_chapter_over_the_oldest_turns() {
      completeTurns(1, 2, 3);

      keeper(open -> null, unwritten, 3).keep(AGENT);

      assertThat(unsummarized()).containsExactly(chapter(1, 3));
    }

    @Test
    void a_policy_that_throws_still_leaves_the_summarising_step_to_run() {
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(chapter(1, 2)))).isTrue();
      completeTurns(1, 2, 3);
      ChapterPolicy throwing =
          open -> {
            throw new IllegalStateException("the policy broke");
          };

      keeper(throwing, SAYS_SOMETHING, 10).keep(AGENT);

      assertThat(chapters.summaries(TYPE, AGENT)).hasSize(1);
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Summarising {

    private void closeChapters(Chapter... closed) {
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(closed))).isTrue();
    }

    @Test
    void every_unsummarised_chapter_is_written_oldest_first() {
      closeChapters(chapter(1, 2), chapter(3, 4), chapter(5, 6));
      List<Chapter> asked = new ArrayList<>();
      Summarizer recording =
          chapter -> {
            asked.add(chapter);
            return "text";
          };

      keeper(open -> List.of(), recording, 10).keep(AGENT);

      assertThat(asked).containsExactly(chapter(1, 2), chapter(3, 4), chapter(5, 6));
      assertThat(chapters.summaries(TYPE, AGENT))
          .extracting(Summary::chapter)
          .containsExactly(chapter(1, 2), chapter(3, 4), chapter(5, 6));
      assertThat(unsummarized()).isEmpty();
    }

    @Test
    void a_blank_summary_is_not_stored_and_stops_the_loop() {
      closeChapters(chapter(1, 2), chapter(3, 4));
      List<Chapter> asked = new ArrayList<>();
      Summarizer blank =
          chapter -> {
            asked.add(chapter);
            return "   ";
          };

      keeper(open -> List.of(), blank, 10).keep(AGENT);

      assertThat(asked).containsExactly(chapter(1, 2));
      assertThat(chapters.summaries(TYPE, AGENT)).isEmpty();
      assertThat(unsummarized()).containsExactly(chapter(1, 2), chapter(3, 4));
    }

    @Test
    void a_summariser_that_throws_stops_the_loop_and_leaves_the_chapter_unsummarised() {
      closeChapters(chapter(1, 2), chapter(3, 4));
      List<Chapter> asked = new ArrayList<>();
      Summarizer throwing =
          chapter -> {
            asked.add(chapter);
            throw new IllegalStateException("the model is down");
          };

      keeper(open -> List.of(), throwing, 10).keep(AGENT);

      assertThat(asked).containsExactly(chapter(1, 2));
      assertThat(unsummarized()).containsExactly(chapter(1, 2), chapter(3, 4));
    }

    @Test
    void a_later_keep_writes_what_an_earlier_one_could_not() {
      closeChapters(chapter(1, 2), chapter(3, 4));
      boolean[] failing = {true};
      Summarizer flaky =
          chapter -> {
            if (failing[0]) {
              throw new IllegalStateException("the model is down");
            }
            return "about " + chapter.through().value();
          };
      ChapterKeeper keeper = keeper(open -> List.of(), flaky, 10);
      keeper.keep(AGENT);
      failing[0] = false;

      keeper.keep(AGENT);

      assertThat(chapters.summaries(TYPE, AGENT)).hasSize(2);
      assertThat(unsummarized()).isEmpty();
    }

    @Test
    void a_chapter_is_never_summarised_twice() {
      closeChapters(chapter(1, 2));
      List<Chapter> asked = new ArrayList<>();
      Summarizer recording =
          chapter -> {
            asked.add(chapter);
            return "text";
          };
      ChapterKeeper keeper = keeper(open -> List.of(), recording, 10);

      keeper.keep(AGENT);
      keeper.keep(AGENT);

      assertThat(asked).containsExactly(chapter(1, 2));
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Leasing {

    /** A lease nobody can take. */
    private final Leases refusing =
        new Leases() {
          @Override
          public <T> Attempt<T> tryWithLease(
              LeaseKind kind, AgentType type, AgentId agent, Duration ttl, Supplier<T> work) {
            return new Attempt.Ignored<>();
          }
        };

    /** A lease that records when it is entered, and when entered while already held. */
    private static final class Watching implements Leases {
      private final InMemoryLeases real = new InMemoryLeases();
      private boolean inside;
      private boolean nested;
      private int entered;

      @Override
      public <T> Attempt<T> tryWithLease(
          LeaseKind kind, AgentType type, AgentId agent, Duration ttl, Supplier<T> work) {
        if (inside) {
          nested = true;
        }
        entered++;
        return real.tryWithLease(
            kind,
            type,
            agent,
            ttl,
            () -> {
              inside = true;
              try {
                return work.get();
              } finally {
                inside = false;
              }
            });
      }
    }

    @Test
    void when_the_lease_is_held_by_someone_else_neither_policy_nor_summariser_is_asked() {
      completeTurns(1, 2, 3);
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(chapter(1, 1)))).isTrue();
      List<String> asked = new ArrayList<>();
      ChapterPolicy policy =
          open -> {
            asked.add("policy");
            return List.of();
          };
      Summarizer summarizer =
          chapter -> {
            asked.add("summariser");
            return "text";
          };

      keeper(policy, summarizer, refusing, 10).keep(AGENT);

      assertThat(asked).isEmpty();
      assertThat(unsummarized()).containsExactly(chapter(1, 1));
    }

    /** A lease that counts every refusal, so a test can tell when a pass has been turned away. */
    private static final class Counting implements Leases {
      private final InMemoryLeases real = new InMemoryLeases();
      private final AtomicInteger refused = new AtomicInteger();

      @Override
      public <T> Attempt<T> tryWithLease(
          LeaseKind kind, AgentType type, AgentId agent, Duration ttl, Supplier<T> work) {
        Attempt<T> attempt = real.tryWithLease(kind, type, agent, ttl, work);
        if (attempt instanceof Attempt.Ignored<T>) {
          refused.incrementAndGet();
        }
        return attempt;
      }
    }

    @Test
    void a_pass_turned_away_by_a_cut_in_progress_still_closes_the_chapter_that_became_due() {
      completeTurns(1, 2);
      CountDownLatch cutting = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      // The first pass reads two open turns and is held there; the policy closes a chapter
      // only once three are open, which the second pass alone can see.
      ChapterPolicy policy =
          open -> {
            if (open.turns().size() == 2) {
              cutting.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            }
            return open.turns().size() >= 3 ? ids(3) : List.of();
          };
      Counting leases = new Counting();
      ChapterKeeper keeper =
          new ChapterKeeper(TYPE, policy, SAYS_SOMETHING, chapters, leases, histories(), 10, TTL);
      CompletableFuture<Void> first = CompletableFuture.runAsync(() -> keeper.keep(AGENT));
      await().atMost(Duration.ofSeconds(10)).until(() -> cutting.getCount() == 0);
      completeTurns(3);
      CompletableFuture<Void> second = CompletableFuture.runAsync(() -> keeper.keep(AGENT));
      await().atMost(Duration.ofSeconds(10)).until(() -> leases.refused.get() >= 1);

      release.countDown();
      first.join();
      second.join();

      assertThat(chapters.closedThrough(TYPE, AGENT)).contains(id(3));
      assertThat(chapters.summaries(TYPE, AGENT))
          .extracting(Summary::chapter)
          .containsExactly(chapter(1, 3));
    }

    @Test
    void
        a_chapter_that_becomes_due_while_a_summary_is_being_written_is_closed_and_summarised_by_the_pass_that_was_writing() {
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(chapter(1, 2)))).isTrue();
      completeTurns(1, 2, 3);
      CountDownLatch writing = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Summarizer summarizer =
          chapter -> {
            if (chapter.equals(chapter(1, 2))) {
              writing.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            }
            return SAYS_SOMETHING.summarize(chapter);
          };
      ChapterPolicy policy = open -> open.turns().size() >= 2 ? ids(4) : List.of();
      Counting leases = new Counting();
      ChapterKeeper keeper = keeper(policy, summarizer, leases, 10);
      CompletableFuture<Void> first = CompletableFuture.runAsync(() -> keeper.keep(AGENT));
      await().atMost(Duration.ofSeconds(10)).until(() -> writing.getCount() == 0);
      completeTurns(4);
      CompletableFuture<Void> second = CompletableFuture.runAsync(() -> keeper.keep(AGENT));

      await().atMost(Duration.ofSeconds(10)).until(second::isDone);
      assertThat(leases.refused).hasValue(1);
      assertThat(first).isNotDone();
      release.countDown();
      first.join();

      assertThat(chapters.closedThrough(TYPE, AGENT)).contains(id(4));
      assertThat(chapters.summaries(TYPE, AGENT))
          .extracting(Summary::chapter)
          .containsExactly(chapter(1, 2), chapter(3, 4));
      assertThat(unsummarized()).isEmpty();
    }

    /** A lease that counts every ask and refuses from the {@code refuseFrom}th on. */
    private static final class Refusing implements Leases {
      private final int refuseFrom;
      private final AtomicInteger asked = new AtomicInteger();

      Refusing(int refuseFrom) {
        this.refuseFrom = refuseFrom;
      }

      @Override
      public <T> Attempt<T> tryWithLease(
          LeaseKind kind, AgentType type, AgentId agent, Duration ttl, Supplier<T> work) {
        if (asked.incrementAndGet() >= refuseFrom) {
          return new Attempt.Ignored<>();
        }
        return new Attempt.Ran<>(work.get());
      }
    }

    @Test
    void a_refused_pass_returns_at_once() {
      completeTurns(1, 2, 3);
      Refusing leases = new Refusing(1);

      keeper(open -> List.of(), SAYS_SOMETHING, leases, 10).keep(AGENT);

      assertThat(leases.asked).hasValue(1);
    }

    @Test
    void a_pass_refused_a_summary_asks_for_nothing_more() {
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(chapter(1, 2)))).isTrue();
      completeTurns(1, 2, 3);
      Refusing leases = new Refusing(2);

      keeper(open -> List.of(), SAYS_SOMETHING, leases, 10).keep(AGENT);

      assertThat(leases.asked).hasValue(2);
      assertThat(unsummarized()).containsExactly(chapter(1, 2));
    }

    @Test
    void a_summariser_that_keeps_failing_is_not_asked_again_in_the_same_pass() {
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(chapter(1, 2)))).isTrue();
      completeTurns(1, 2);
      List<Chapter> asked = new ArrayList<>();
      // A turn completes while the summary is being written, so the pass looks again.
      Summarizer failing =
          chapter -> {
            asked.add(chapter);
            completeTurns(3);
            throw new IllegalStateException("the model is down");
          };

      keeper(open -> List.of(), failing, 10).keep(AGENT);

      assertThat(asked).containsExactly(chapter(1, 2));
      assertThat(unsummarized()).containsExactly(chapter(1, 2));
    }

    @Test
    void a_policy_that_closes_nothing_is_not_asked_again_when_nothing_changed() {
      completeTurns(1, 2, 3);
      AtomicInteger asked = new AtomicInteger();
      ChapterPolicy closesNothing =
          open -> {
            asked.incrementAndGet();
            return List.of();
          };

      keeper(closesNothing, SAYS_SOMETHING, 10).keep(AGENT);

      assertThat(asked).hasValue(1);
    }

    @Test
    void turns_left_open_by_a_cut_are_not_taken_for_new_ones() {
      completeTurns(1, 2, 3);
      AtomicInteger asked = new AtomicInteger();
      ChapterPolicy closesTheFirstTwo =
          open -> {
            asked.incrementAndGet();
            return open.turns().size() == 3 ? ids(2) : List.of();
          };

      keeper(closesTheFirstTwo, SAYS_SOMETHING, 10).keep(AGENT);

      assertThat(chapters.closedThrough(TYPE, AGENT)).contains(id(2));
      assertThat(asked).hasValue(1);
    }

    @Test
    void a_chapter_summarised_by_someone_else_before_the_work_runs_is_not_summarised_again() {
      Chapter closed = chapter(1, 2);
      assertThat(chapters.append(TYPE, AGENT, Optional.empty(), List.of(closed))).isTrue();
      InMemoryLeases real = new InMemoryLeases();
      Leases rival =
          new Leases() {
            @Override
            public <T> Attempt<T> tryWithLease(
                LeaseKind kind, AgentType type, AgentId agent, Duration ttl, Supplier<T> work) {
              return real.tryWithLease(
                  kind,
                  type,
                  agent,
                  ttl,
                  () -> {
                    chapters.summarize(new Summary(closed, "first"));
                    return work.get();
                  });
            }
          };
      List<Chapter> asked = new ArrayList<>();
      Summarizer recording =
          chapter -> {
            asked.add(chapter);
            return "second";
          };

      keeper(open -> List.of(), recording, rival, 10).keep(AGENT);

      assertThat(asked).isEmpty();
      assertThat(chapters.summaries(TYPE, AGENT))
          .extracting(Summary::text)
          .containsExactly("first");
    }

    @Test
    void the_policy_and_the_summariser_are_each_called_inside_the_lease_and_never_nested() {
      completeTurns(1, 2);
      Watching leases = new Watching();
      List<Boolean> insideWhenAsked = new ArrayList<>();
      ChapterPolicy policy =
          open -> {
            insideWhenAsked.add(leases.inside);
            return ids(2);
          };
      Summarizer summarizer =
          chapter -> {
            insideWhenAsked.add(leases.inside);
            return "text";
          };

      keeper(policy, summarizer, leases, 10).keep(AGENT);

      assertThat(insideWhenAsked).containsExactly(true, true);
      assertThat(leases.entered).isEqualTo(2);
      assertThat(leases.nested).isFalse();
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Listening {

    private final List<Chapter> asked = new CopyOnWriteArrayList<>();

    private ChapterKeeper keeper() {
      completeTurns(1, 2);
      Summarizer recording =
          chapter -> {
            asked.add(chapter);
            return "text";
          };
      return ChapterKeeperTest.this.keeper(open -> ids(2), recording, 10);
    }

    @Test
    void the_listener_keeps_the_agent_whose_turn_ended_on_this_type() {
      NarrationListener listener = keeper().listener();

      listener.on(stopped(TYPE, id(2)));

      await().atMost(Duration.ofSeconds(10)).until(() -> !asked.isEmpty());
      assertThat(asked).containsExactly(chapter(1, 2));
    }

    @Test
    void the_listener_keeps_nothing_for_a_turn_that_ended_on_another_type() {
      NarrationListener.Async async = (NarrationListener.Async) keeper().listener();
      NarrationListener told = async.delegate();
      AgentType other = new AgentType("other");

      told.on(stopped(other, id(2)));

      assertThat(asked).isEmpty();
      assertThat(chapters.closedThrough(TYPE, AGENT)).isEmpty();
    }

    @Test
    void the_listener_keeps_nothing_for_an_event_that_is_not_a_turn_ending() {
      NarrationListener.Async async = (NarrationListener.Async) keeper().listener();
      NarrationListener told = async.delegate();

      told.on(
          Narrated.story(
              TYPE,
              AGENT,
              new Narration.TurnStarted(id(3), "Question", Instant.EPOCH),
              new Seq(1),
              Instant.EPOCH));

      assertThat(asked).isEmpty();
      assertThat(chapters.closedThrough(TYPE, AGENT)).isEmpty();
    }

    @Test
    void the_listener_keeps_the_agent_when_told_directly_of_a_turn_ending_on_this_type() {
      NarrationListener.Async async = (NarrationListener.Async) keeper().listener();
      NarrationListener told = async.delegate();

      told.on(stopped(TYPE, id(2)));

      assertThat(asked).containsExactly(chapter(1, 2));
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Constructing {

    @Test
    void a_maximum_below_one_is_rejected() {
      assertThatThrownBy(() -> keeper(open -> List.of(), SAYS_SOMETHING, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_lease_time_that_is_not_positive_is_rejected() {
      ChapterPolicy policy = open -> List.of();
      Leases leases = new InMemoryLeases();
      TurnHistories histories = histories();

      assertThatThrownBy(
              () ->
                  new ChapterKeeper(
                      TYPE, policy, SAYS_SOMETHING, chapters, leases, histories, 3, Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static Narrated stopped(AgentType type, TurnId turn) {
    return Narrated.story(
        type, AGENT, new Narration.TurnStopped(turn, "limit"), new Seq(2), Instant.EPOCH);
  }
}
