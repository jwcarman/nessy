package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.BacklogItem;

class WatchmanObservationsTest {

  private static final String TICK = "It is 03:00. Do your rounds.";
  private static final String NEWS = "The disk filled up.";

  private static BacklogItem<String> item(String observation, long second) {
    return new BacklogItem<>(observation, Instant.EPOCH.plusSeconds(second));
  }

  private static List<BacklogItem<String>> kept(
      List<BacklogItem<String>> waiting, BacklogItem<String> arriving) {
    return WatchmanObservations.COALESCER.coalesce(waiting, arriving);
  }

  @Test
  void a_tick_arriving_on_an_empty_backlog_is_simply_kept() {
    assertThat(kept(List.of(), item(TICK, 1))).containsExactly(item(TICK, 1));
  }

  @Test
  void a_newer_tick_supersedes_a_waiting_one() {
    // A watchman busy for an hour does one round of catching up, not twenty.
    assertThat(kept(List.of(item(TICK, 1)), item(TICK, 2))).containsExactly(item(TICK, 2));
  }

  @Test
  void many_waiting_ticks_all_collapse_into_the_newest() {
    List<BacklogItem<String>> waiting = List.of(item(TICK, 1), item(TICK, 2), item(TICK, 3));
    assertThat(kept(waiting, item(TICK, 4))).containsExactly(item(TICK, 4));
  }

  @Test
  void anything_that_is_not_a_tick_is_kept_alongside() {
    // Only ticks supersede: a real event must never be swallowed by the next cron beat.
    assertThat(kept(List.of(item(TICK, 1)), item(NEWS, 2)))
        .containsExactly(item(TICK, 1), item(NEWS, 2));
  }

  @Test
  void a_tick_does_not_supersede_a_waiting_real_event() {
    assertThat(kept(List.of(item(NEWS, 1)), item(TICK, 2)))
        .containsExactly(item(NEWS, 1), item(TICK, 2));
  }
}
