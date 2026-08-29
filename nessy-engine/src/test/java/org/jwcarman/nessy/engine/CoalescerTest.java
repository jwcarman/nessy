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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The backlog reducer, exercised with no actor in sight — which is the point of it being a pure
 * function rather than something only observable through a running agent.
 */
@DisplayName("Deciding what to keep when observations pile up")
class CoalescerTest {

  private static final Instant NOON = Instant.parse("2026-08-28T12:00:00Z");

  /** A cron tick: always the same request, so twenty of them are one of them. */
  private record Tick(String text) {}

  /** A quote: the latest price per symbol is the only one worth having. */
  private record Quote(String symbol, int price) {}

  /** Something a person said: never merged with anything, ever. */
  private record Said(String text) {}

  private static Backlog.Entry<Object> entry(String id, Object observation, int secondsPastNoon) {
    return new Backlog.Entry<>(id, observation, NOON.plusSeconds(secondsPastNoon));
  }

  /**
   * Takes a List rather than varargs on purpose: a generic varargs parameter creates a generic
   * array, which is a heap-pollution warning, and this repository fixes warnings rather than
   * annotating them away.
   */
  private static Backlog<Object> ingestAll(
      Coalescer<Object> coalescer, Backlog<Object> start, List<Backlog.Entry<Object>> arrivals) {
    Backlog<Object> backlog = start;
    for (Backlog.Entry<Object> arrival : arrivals) {
      backlog = coalescer.ingest(backlog, arrival);
    }
    return backlog;
  }

  @Test
  void without_a_coalescer_every_observation_accumulates() {
    Backlog<Object> backlog =
        ingestAll(
            Coalescer.none(),
            Backlog.empty(),
            List.of(
                entry("1", new Said("first"), 0),
                entry("2", new Said("second"), 1),
                entry("3", new Said("third"), 2)));

    assertThat(backlog.observations())
        .containsExactly(new Said("first"), new Said("second"), new Said("third"));
  }

  @Test
  void twenty_cron_ticks_become_one_tick() {
    Coalescer<Object> coalescer = Coalescer.byKey(o -> Optional.of("rounds"));

    Backlog<Object> backlog = Backlog.empty();
    for (int i = 0; i < 20; i++) {
      backlog = coalescer.ingest(backlog, entry("tick-" + i, new Tick("tick " + i), i));
    }

    assertThat(backlog.size()).isEqualTo(1);
    assertThat(backlog.observations()).containsExactly(new Tick("tick 19"));
  }

  @Test
  void a_quote_keeps_only_the_latest_price_per_symbol() {
    Coalescer<Object> coalescer =
        Coalescer.byKey(o -> o instanceof Quote q ? Optional.of(q.symbol()) : Optional.empty());

    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(
                entry("a", new Quote("AAPL", 100), 0),
                entry("b", new Quote("MSFT", 200), 1),
                entry("c", new Quote("AAPL", 111), 2)));

    assertThat(backlog.observations())
        .containsExactly(new Quote("AAPL", 111), new Quote("MSFT", 200));
  }

  @Test
  void a_superseded_entry_keeps_its_position_rather_than_moving_to_the_end() {
    Coalescer<Object> coalescer =
        Coalescer.byKey(o -> o instanceof Quote q ? Optional.of(q.symbol()) : Optional.empty());

    // AAPL arrives first, then MSFT, then AAPL again. A chatty symbol must not outrank an older
    // one merely by being noisy, so AAPL stays in front.
    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(
                entry("a", new Quote("AAPL", 100), 0),
                entry("b", new Quote("MSFT", 200), 1),
                entry("c", new Quote("AAPL", 111), 30)));

    assertThat(backlog.observations()).element(0).isEqualTo(new Quote("AAPL", 111));
  }

  @Test
  void a_superseded_entry_keeps_the_time_the_topic_first_arrived() {
    Coalescer<Object> coalescer = Coalescer.byKey(o -> Optional.of("rounds"));

    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(entry("first", new Tick("first"), 0), entry("later", new Tick("later"), 300)));

    // Staleness policies read receivedAt, so refreshing it on every supersede would make a
    // continuously-updated topic look eternally fresh.
    assertThat(backlog.entries())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.receivedAt()).isEqualTo(NOON);
              assertThat(e.observation()).isEqualTo(new Tick("later"));
            });
  }

  @Test
  void
      a_superseded_entry_takes_the_new_arrivals_id_but_keeps_its_position_and_first_arrival_time() {
    Coalescer<Object> coalescer =
        Coalescer.byKey(o -> o instanceof Quote q ? Optional.of(q.symbol()) : Optional.empty());

    // AAPL arrives, then MSFT, then AAPL supersedes again -- the exact shape that let the soak's
    // "rounds" tick reuse one entry id (and therefore one Remembrance key) forever.
    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(
                entry("a", new Quote("AAPL", 100), 0),
                entry("b", new Quote("MSFT", 200), 1),
                entry("c", new Quote("AAPL", 111), 30)));

    assertThat(backlog.entries()).isNotEmpty();
    assertThat(backlog.entries())
        .element(0)
        .satisfies(
            e -> {
              // The id CHANGES to the superseding arrival's -- the property whose absence let
              // every coalesced observation reuse one Remembrance key forever.
              assertThat(e.id()).isEqualTo("c");
              assertThat(e.id()).isNotEqualTo("a");
              // Position and receivedAt still describe when the topic first showed up.
              assertThat(e.receivedAt()).isEqualTo(NOON);
              assertThat(e.observation()).isEqualTo(new Quote("AAPL", 111));
            });
  }

  @Test
  void a_merge_can_fold_rather_than_replace() {
    record Errors(String kind, int count) {}
    Coalescer<Object> coalescer =
        Coalescer.byKey(
            o -> o instanceof Errors e ? Optional.of(e.kind()) : Optional.empty(),
            (existing, incoming) ->
                new Errors(
                    ((Errors) existing).kind(),
                    ((Errors) existing).count() + ((Errors) incoming).count()));

    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(
                entry("1", new Errors("disk", 1), 0),
                entry("2", new Errors("disk", 1), 1),
                entry("3", new Errors("disk", 3), 2)));

    assertThat(backlog.observations()).containsExactly(new Errors("disk", 5));
  }

  @Test
  void an_absent_key_accumulates_even_under_a_keyed_coalescer() {
    Coalescer<Object> coalescer =
        Coalescer.byKey(o -> o instanceof Quote q ? Optional.of(q.symbol()) : Optional.empty());

    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(
                entry("1", new Said("hello"), 0),
                entry("2", new Said("are you there"), 1),
                entry("3", new Quote("AAPL", 100), 2)));

    // Two things a person said stay two things a person said.
    assertThat(backlog.observations())
        .containsExactly(new Said("hello"), new Said("are you there"), new Quote("AAPL", 100));
  }

  @Test
  void a_custom_reduction_can_clear_the_whole_backlog() {
    record Cancel() {}
    Coalescer<Object> coalescer =
        (current, incoming) ->
            incoming.observation() instanceof Cancel
                ? Backlog.empty()
                : current.append(incoming.id(), incoming.observation(), incoming.receivedAt());

    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(
                entry("1", new Said("do the thing"), 0),
                entry("2", new Said("and the other thing"), 1)));
    assertThat(backlog.observations()).isNotEmpty();

    Backlog<Object> afterCancel = coalescer.ingest(backlog, entry("3", new Cancel(), 2));

    // Cross-key logic is exactly what a key-and-merge pair cannot express.
    assertThat(afterCancel.observations()).isEmpty();
  }

  @Test
  void a_custom_reduction_can_ignore_an_observation_entirely() {
    record Heartbeat() {}
    Coalescer<Object> coalescer =
        (current, incoming) ->
            incoming.observation() instanceof Heartbeat
                ? current
                : current.append(incoming.id(), incoming.observation(), incoming.receivedAt());

    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(entry("1", new Heartbeat(), 0), entry("2", new Heartbeat(), 1)));

    // A heartbeat updates nothing, so it must not be able to wake an idle agent.
    assertThat(backlog.observations()).isEmpty();
  }

  @Test
  void staleness_is_expressible_without_the_reducer_reading_a_clock() {
    Duration maxAge = Duration.ofMinutes(5);
    Coalescer<Object> coalescer =
        (current, incoming) -> {
          Backlog<Object> fresh = current;
          for (Backlog.Entry<Object> existing : current.entries()) {
            if (Duration.between(existing.receivedAt(), incoming.receivedAt()).compareTo(maxAge)
                > 0) {
              fresh = fresh.remove(existing.id());
            }
          }
          return fresh.append(incoming.id(), incoming.observation(), incoming.receivedAt());
        };

    Backlog<Object> backlog =
        ingestAll(
            coalescer,
            Backlog.empty(),
            List.of(
                entry("old", new Quote("AAPL", 100), 0),
                entry("new", new Quote("AAPL", 111), 600)));

    // The arriving entry carries the only clock reading the function ever needs.
    assertThat(backlog.observations()).containsExactly(new Quote("AAPL", 111));
  }
}
