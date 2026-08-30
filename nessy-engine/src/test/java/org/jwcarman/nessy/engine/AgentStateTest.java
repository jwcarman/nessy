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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;

@DisplayName("What an agent persists")
class AgentStateTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");

  private static BacklogItem<String> item(String id, String observation) {
    return new BacklogItem<>(id, observation, Instant.EPOCH);
  }

  /** Keeps everything, in arrival order. */
  private static BacklogCoalescer<String> keepAll() {
    return (waiting, arrival) -> {
      List<BacklogItem<String>> all = new ArrayList<>(waiting);
      all.add(arrival);
      return all;
    };
  }

  /** Only the newest survives — the policy that would eat an in-flight observation. */
  private static BacklogCoalescer<String> newestOnly() {
    return (waiting, arrival) -> List.of(arrival);
  }

  @Nested
  class Ingesting {

    @Test
    void a_keeping_coalescer_accumulates() {
      AgentState<String> state =
          AgentState.<String>idle(WATCHMAN)
              .ingesting(keepAll(), item("a", "one"))
              .ingesting(keepAll(), item("b", "two"));

      assertThat(state.backlog())
          .extracting(BacklogItem::observation)
          .containsExactly("one", "two");
    }

    @Test
    void a_superseding_coalescer_keeps_only_what_it_returns() {
      AgentState<String> state =
          AgentState.<String>idle(WATCHMAN)
              .ingesting(newestOnly(), item("a", "one"))
              .ingesting(newestOnly(), item("b", "two"));

      assertThat(state.backlog()).extracting(BacklogItem::observation).containsExactly("two");
    }
  }

  @Nested
  class Taking {

    @Test
    void moves_the_head_into_flight_and_names_the_turn() {
      AgentState<String> state =
          AgentState.<String>idle(WATCHMAN)
              .ingesting(keepAll(), item("a", "one"))
              .ingesting(keepAll(), item("b", "two"))
              .taking("turn-1");

      assertThat(state.inFlight().observation()).isEqualTo("one");
      assertThat(state.backlog()).extracting(BacklogItem::observation).containsExactly("two");
      assertThat(state.turnId()).isEqualTo("turn-1");
      assertThat(state.busy()).isTrue();
    }

    @Test
    @DisplayName("the coalescer never sees the observation a turn is running on")
    void an_in_flight_observation_is_out_of_the_coalescers_reach() {
      AgentState<String> working =
          AgentState.<String>idle(WATCHMAN).ingesting(keepAll(), item("a", "one")).taking("turn-1");

      AgentState<String> after = working.ingesting(newestOnly(), item("b", "two"));

      assertThat(after.inFlight().observation()).isEqualTo("one");
      assertThat(after.backlog()).extracting(BacklogItem::observation).containsExactly("two");
    }

    @Test
    void taking_from_an_empty_backlog_is_a_caller_bug() {
      AgentState<String> idle = AgentState.idle(WATCHMAN);

      assertThatThrownBy(() -> idle.taking("turn-1")).isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  class Finishing {

    @Test
    void clears_the_turn_and_the_in_flight_slot_but_not_the_backlog() {
      AgentState<String> state =
          AgentState.<String>idle(WATCHMAN)
              .ingesting(keepAll(), item("a", "one"))
              .ingesting(keepAll(), item("b", "two"))
              .taking("turn-1")
              .finished();

      assertThat(state.inFlight()).isNull();
      assertThat(state.busy()).isFalse();
      assertThat(state.hasWork()).isTrue();
      assertThat(state.backlog()).extracting(BacklogItem::observation).containsExactly("two");
    }
  }
}
