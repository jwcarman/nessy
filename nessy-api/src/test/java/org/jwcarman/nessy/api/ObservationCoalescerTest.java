package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A coalescer")
class ObservationCoalescerTest {

  private record Reading(String sensor, int value) {}

  private static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");

  private static BacklogItem<Reading> at(Instant when, String sensor, int value) {
    return new BacklogItem<>(new Reading(sensor, value), when);
  }

  private static List<BacklogItem<Reading>> waiting() {
    return List.of(at(T0, "porch", 1), at(T0.plusSeconds(1), "hall", 2));
  }

  @Test
  void keep_all_appends() {
    List<BacklogItem<Reading>> next =
        ObservationCoalescer.<Reading>keepAll()
            .coalesce(waiting(), at(T0.plusSeconds(2), "porch", 3));

    assertThat(next).extracting(item -> item.observation().value()).containsExactly(1, 2, 3);
  }

  @Test
  void replace_by_key_keeps_the_newest_per_key_in_place_and_appends_a_new_key() {
    ObservationCoalescer<Reading> coalescer = ObservationCoalescer.replaceBy(Reading::sensor);

    List<BacklogItem<Reading>> replaced =
        coalescer.coalesce(waiting(), at(T0.plusSeconds(2), "porch", 3));
    List<BacklogItem<Reading>> appended =
        coalescer.coalesce(waiting(), at(T0.plusSeconds(2), "garage", 4));

    assertThat(replaced).extracting(item -> item.observation().value()).containsExactly(3, 2);
    assertThat(appended).extracting(item -> item.observation().value()).containsExactly(1, 2, 4);
  }

  @Test
  void drop_repeats_ignores_a_key_already_waiting() {
    ObservationCoalescer<Reading> coalescer = ObservationCoalescer.dropRepeats(Reading::sensor);

    List<BacklogItem<Reading>> dropped =
        coalescer.coalesce(waiting(), at(T0.plusSeconds(2), "porch", 3));
    List<BacklogItem<Reading>> kept =
        coalescer.coalesce(waiting(), at(T0.plusSeconds(2), "garage", 4));

    assertThat(dropped).extracting(item -> item.observation().value()).containsExactly(1, 2);
    assertThat(kept).hasSize(3);
  }

  @Test
  void merge_by_folds_into_the_waiting_item_and_keeps_its_arrival_time() {
    ObservationCoalescer<Reading> coalescer =
        ObservationCoalescer.mergeBy(
            Reading::sensor, (a, b) -> new Reading(a.sensor(), a.value() + b.value()));

    List<BacklogItem<Reading>> merged =
        coalescer.coalesce(waiting(), at(T0.plusSeconds(2), "porch", 3));
    List<BacklogItem<Reading>> appended =
        coalescer.coalesce(waiting(), at(T0.plusSeconds(2), "garage", 4));

    assertThat(merged.getFirst().observation()).isEqualTo(new Reading("porch", 4));
    assertThat(merged.getFirst().arrivedAt()).isEqualTo(T0);
    assertThat(appended).hasSize(3);
  }

  @Test
  void expiring_forgets_what_arrived_before_the_ttl() {
    ObservationCoalescer<Reading> coalescer =
        ObservationCoalescer.<Reading>keepAll().expiring(Duration.ofSeconds(1));

    List<BacklogItem<Reading>> next =
        coalescer.coalesce(waiting(), at(T0.plusSeconds(2), "porch", 3));

    assertThat(next).extracting(item -> item.observation().value()).containsExactly(2, 3);
  }

  @Test
  void capped_keeps_the_newest() {
    ObservationCoalescer<Reading> coalescer = ObservationCoalescer.<Reading>keepAll().capped(2);

    List<BacklogItem<Reading>> next =
        coalescer.coalesce(waiting(), at(T0.plusSeconds(2), "porch", 3));
    List<BacklogItem<Reading>> roomy = coalescer.coalesce(List.of(), at(T0, "porch", 3));

    assertThat(next).extracting(item -> item.observation().value()).containsExactly(2, 3);
    assertThat(roomy).hasSize(1);
  }
}
