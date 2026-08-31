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

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.message.UserMessage;

/** The one policy this application has about its own observations: ticks supersede one another. */
class WatchmanObservationsTest {

  private static final String TICK = "It is 03:00. Do your rounds.";

  private static BacklogItem<String> item(String id, String observation) {
    return new BacklogItem<>(id, observation, Instant.EPOCH);
  }

  @Test
  void a_tick_arriving_on_an_empty_backlog_is_simply_kept() {
    List<BacklogItem<String>> kept =
        WatchmanObservations.COALESCER.coalesce(List.of(), item("a", TICK));

    assertThat(kept).extracting(BacklogItem::id).containsExactly("a");
  }

  @Test
  void a_newer_tick_supersedes_a_waiting_one() {
    List<BacklogItem<String>> waiting = List.of(item("old", TICK));

    List<BacklogItem<String>> kept =
        WatchmanObservations.COALESCER.coalesce(waiting, item("new", TICK));

    // A watchman busy for an hour does one round of catching up, not twenty.
    assertThat(kept).extracting(BacklogItem::id).containsExactly("new");
  }

  @Test
  void many_waiting_ticks_all_collapse_into_the_newest() {
    List<BacklogItem<String>> waiting =
        List.of(item("one", TICK), item("two", TICK), item("three", TICK));

    List<BacklogItem<String>> kept =
        WatchmanObservations.COALESCER.coalesce(waiting, item("four", TICK));

    assertThat(kept).extracting(BacklogItem::id).containsExactly("four");
  }

  @Test
  void anything_that_is_not_a_tick_is_kept_alongside() {
    List<BacklogItem<String>> waiting = List.of(item("tick", TICK));

    List<BacklogItem<String>> kept =
        WatchmanObservations.COALESCER.coalesce(waiting, item("news", "The disk filled up."));

    // Only ticks supersede: a real event must never be swallowed by the next cron beat.
    assertThat(kept).extracting(BacklogItem::id).containsExactly("tick", "news");
  }

  @Test
  void a_tick_does_not_supersede_a_waiting_real_event() {
    List<BacklogItem<String>> waiting = List.of(item("news", "The disk filled up."));

    List<BacklogItem<String>> kept =
        WatchmanObservations.COALESCER.coalesce(waiting, item("tick", TICK));

    assertThat(kept).extracting(BacklogItem::id).containsExactly("news", "tick");
  }

  @Test
  void an_observation_renders_as_one_user_message() {
    assertThat(WatchmanObservations.RENDERER.render("The disk filled up."))
        .isEqualTo(UserMessage.of("The disk filled up."));
  }
}
