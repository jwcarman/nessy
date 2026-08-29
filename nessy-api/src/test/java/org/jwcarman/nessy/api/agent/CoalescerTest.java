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
package org.jwcarman.nessy.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Grooming a backlog: what merges, what supersedes, and what a policy is never allowed to lose. */
class CoalescerTest {

  private static final Instant T0 = Instant.parse("2026-08-28T12:00:00Z");

  private static BacklogItem<String> item(String id, String observation, int secondsLater) {
    return new BacklogItem<>(id, observation, T0.plusSeconds(secondsLater));
  }

  private static List<BacklogItem<String>> ingestAll(
      Coalescer<String> coalescer, List<BacklogItem<String>> arrivals) {
    List<BacklogItem<String>> backlog = List.of();
    for (BacklogItem<String> arrival : arrivals) {
      backlog = coalescer.ingest(backlog, arrival);
    }
    return backlog;
  }

  private static List<String> observations(List<BacklogItem<String>> backlog) {
    return backlog.stream().map(BacklogItem::observation).toList();
  }

  @Nested
  class Without_a_policy {

    @Test
    void every_observation_accumulates() {
      List<BacklogItem<String>> arrivals =
          List.of(item("1", "a", 0), item("2", "b", 1), item("3", "c", 2));

      assertThat(observations(ingestAll(Coalescer.none(), arrivals)))
          .containsExactly("a", "b", "c");
    }
  }

  @Nested
  class Keyed {

    private final Coalescer<String> ticks =
        Coalescer.byKey(text -> text.endsWith("rounds") ? Optional.of("rounds") : Optional.empty());

    @Test
    void twenty_cron_ticks_become_one() {
      List<BacklogItem<String>> arrivals = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        arrivals.add(item("tick-" + i, "do your rounds", i));
      }

      List<BacklogItem<String>> backlog = ingestAll(ticks, arrivals);

      assertThat(backlog).hasSize(1);
      assertThat(backlog.getFirst().id()).isEqualTo("tick-19");
    }

    @Test
    void a_superseded_item_keeps_its_position_rather_than_moving_to_the_end() {
      List<BacklogItem<String>> arrivals =
          List.of(
              item("1", "do your rounds", 0),
              item("2", "disk is full", 1),
              item("3", "do your rounds", 2));

      assertThat(observations(ingestAll(ticks, arrivals)))
          .containsExactly("do your rounds", "disk is full");
    }

    @Test
    void a_superseded_item_keeps_the_time_the_topic_first_arrived() {
      List<BacklogItem<String>> arrivals =
          List.of(item("1", "do your rounds", 0), item("2", "do your rounds", 300));

      List<BacklogItem<String>> backlog = ingestAll(ticks, arrivals);

      assertThat(backlog.getFirst().receivedAt()).isEqualTo(T0);
    }

    @Test
    void a_superseded_item_takes_the_new_id_because_a_merged_item_is_a_new_item() {
      List<BacklogItem<String>> arrivals =
          List.of(item("first", "do your rounds", 0), item("second", "do your rounds", 1));

      assertThat(ingestAll(ticks, arrivals).getFirst().id()).isEqualTo("second");
    }

    @Test
    void an_absent_key_accumulates_even_under_a_keyed_policy() {
      List<BacklogItem<String>> arrivals =
          List.of(item("1", "disk is full", 0), item("2", "memory is low", 1));

      assertThat(observations(ingestAll(ticks, arrivals)))
          .containsExactly("disk is full", "memory is low");
    }

    @Test
    void a_merge_can_fold_rather_than_replace() {
      Coalescer<String> folding =
          Coalescer.byKey(
              text -> Optional.of("all"), (existing, incoming) -> existing + " | " + incoming);
      List<BacklogItem<String>> arrivals =
          List.of(item("1", "a", 0), item("2", "b", 1), item("3", "c", 2));

      assertThat(observations(ingestAll(folding, arrivals))).containsExactly("a | b | c");
    }
  }

  @Nested
  class A_custom_policy {

    @Test
    void can_drop_an_observation_entirely() {
      Coalescer<String> ignoreNoise =
          (current, incoming) ->
              incoming.observation().equals("noise") ? current : append(current, incoming);
      List<BacklogItem<String>> arrivals =
          List.of(item("1", "real", 0), item("2", "noise", 1), item("3", "also real", 2));

      assertThat(observations(ingestAll(ignoreNoise, arrivals)))
          .containsExactly("real", "also real");
    }

    @Test
    void expresses_staleness_without_reading_a_clock() {
      Coalescer<String> fresh =
          (current, incoming) ->
              append(
                  current.stream()
                      .filter(
                          item ->
                              !item.receivedAt().isBefore(incoming.receivedAt().minusSeconds(60)))
                      .toList(),
                  incoming);
      List<BacklogItem<String>> arrivals =
          List.of(item("old", "stale", 0), item("new", "fresh", 3600));

      assertThat(observations(ingestAll(fresh, arrivals))).containsExactly("fresh");
    }

    private static List<BacklogItem<String>> append(
        List<BacklogItem<String>> current, BacklogItem<String> incoming) {
      List<BacklogItem<String>> next = new ArrayList<>(current);
      next.add(incoming);
      return List.copyOf(next);
    }
  }
}
