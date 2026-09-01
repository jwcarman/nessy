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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.testing.TestDatabase;

@DisplayName("What is waiting to become a turn")
class BacklogStoreTest {

  private static final AgentId AGENT = AgentId.of("watchman");

  private DataSource dataSource;
  private Claims claims;

  /** Keeps everything, in arrival order. */
  private static BacklogCoalescer<String> keepAll() {
    return (waiting, arrival) -> {
      List<BacklogItem<String>> all = new ArrayList<>(waiting);
      all.add(arrival);
      return all;
    };
  }

  /** Only the newest survives — the shape of the watchman's heartbeat. */
  private static BacklogCoalescer<String> newestOnly() {
    return (waiting, arrival) -> List.of(arrival);
  }

  /** Newest first, which no timestamp ordering would ever produce. */
  private static BacklogCoalescer<String> newestFirst() {
    return (waiting, arrival) -> {
      List<BacklogItem<String>> all = new ArrayList<>();
      all.add(arrival);
      all.addAll(waiting);
      return all;
    };
  }

  private BacklogStore<String> storeWith(BacklogCoalescer<String> coalescer) {
    ObjectMapper mapper = EngineMapper.create();
    return new BacklogStore<>(
        dataSource,
        claims,
        JsonCodec.of(mapper, String.class),
        JsonCodec.of(mapper, UserMessage.class),
        UserMessage::of,
        coalescer,
        Clock.system(ZoneOffset.UTC));
  }

  /** What the model would be asked, read back out of the claim the take wrote. */
  private String claimed(BacklogStore.Taken taken) {
    ObjectMapper mapper = EngineMapper.create();
    byte[] payload = claims.get(AGENT, taken.turnId(), taken.observationClaim()).orElseThrow();
    UserMessage message = JsonCodec.of(mapper, UserMessage.class).decode(payload);
    return message.content().stream()
        .filter(TextBlock.class::isInstance)
        .map(TextBlock.class::cast)
        .map(TextBlock::text)
        .collect(Collectors.joining());
  }

  @BeforeEach
  void freshDatabase() {
    dataSource = TestDatabase.fresh();
    claims = new Claims(dataSource);
  }

  @Nested
  class Offering {

    @Test
    void a_take_from_an_empty_backlog_hands_back_nothing() {
      assertThat(storeWith(keepAll()).take(AGENT, null)).isEmpty();
    }

    @Test
    void what_is_offered_first_is_taken_first() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "one");
      store.offer(AGENT, "two");

      assertThat(claimed(store.take(AGENT, null).orElseThrow())).isEqualTo("one");
    }

    @Test
    void a_superseding_coalescer_leaves_one_row_no_matter_how_many_arrive() {
      BacklogStore<String> store = storeWith(newestOnly());
      store.offer(AGENT, "tick one");
      store.offer(AGENT, "tick two");
      store.offer(AGENT, "tick three");

      BacklogStore.Taken taken = store.take(AGENT, null).orElseThrow();

      assertThat(claimed(taken)).isEqualTo("tick three");
      assertThat(store.take(AGENT, taken.turnId())).isEmpty();
    }

    @Test
    void the_coalescer_decides_what_comes_next_rather_than_the_clock() {
      BacklogStore<String> store = storeWith(newestFirst());
      store.offer(AGENT, "first to arrive");
      store.offer(AGENT, "last to arrive");

      assertThat(claimed(store.take(AGENT, null).orElseThrow())).isEqualTo("last to arrive");
    }
  }

  @Nested
  class TakingAndSweeping {

    @Test
    void the_turn_id_is_the_row_id_so_an_unrecorded_take_hands_the_same_one_back() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "one");

      BacklogStore.Taken first = store.take(AGENT, null).orElseThrow();
      BacklogStore.Taken again = store.take(AGENT, null).orElseThrow();

      assertThat(again.turnId())
          .as("the agent died before recording the take; nobody named the row, so it comes back")
          .isEqualTo(first.turnId());
    }

    @Test
    void naming_the_finished_turn_sweeps_that_row_and_moves_on() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "one");
      store.offer(AGENT, "two");

      BacklogStore.Taken first = store.take(AGENT, null).orElseThrow();
      BacklogStore.Taken second = store.take(AGENT, first.turnId()).orElseThrow();

      assertThat(second.turnId()).isNotEqualTo(first.turnId());
      assertThat(claimed(second)).isEqualTo("two");
    }

    @Test
    void sweeping_the_last_row_leaves_nothing_waiting() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "only");

      BacklogStore.Taken taken = store.take(AGENT, null).orElseThrow();
      Optional<BacklogStore.Taken> nothing = store.take(AGENT, taken.turnId());

      assertThat(nothing).isEmpty();
    }

    @Test
    void a_sweep_takes_the_finished_turns_claims_with_it() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "one");
      BacklogStore.Taken taken = store.take(AGENT, null).orElseThrow();

      store.take(AGENT, taken.turnId());

      assertThat(claims.get(AGENT, taken.turnId(), taken.observationClaim())).isEmpty();
    }

    @Test
    void a_superseding_coalescer_cannot_merge_away_the_observation_being_worked_on() {
      BacklogStore<String> store = storeWith(newestOnly());
      store.offer(AGENT, "being worked");
      BacklogStore.Taken taken = store.take(AGENT, null).orElseThrow();

      store.offer(AGENT, "arriving later");

      assertThat(claimed(taken))
          .as("the taken row is not waiting, so the coalescer never sees it")
          .isEqualTo("being worked");
    }
  }
}
