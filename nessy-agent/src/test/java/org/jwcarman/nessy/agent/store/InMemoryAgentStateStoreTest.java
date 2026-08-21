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
package org.jwcarman.nessy.agent.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.State;
import org.jwcarman.nessy.agent.support.TestClock;

class InMemoryAgentStateStoreTest {

  @Test
  void aFreshScopeLoadsTheInitialState() {
    assertThat(new InMemoryAgentStateStore().load()).isEqualTo(State.initial());
  }

  @Test
  void aSaveAdvancesTheVersionByExactlyOne() {
    var store = new InMemoryAgentStateStore();
    store.save(new State(new Phase.AwaitingModel(), store.load().version()));
    assertThat(store.load()).isEqualTo(new State(new Phase.AwaitingModel(), 1L));
  }

  @Test
  void aSaveAgainstAStaleVersionIsRefused() {
    var store = new InMemoryAgentStateStore();
    store.save(new State(new Phase.AwaitingModel(), 0L)); // stored version is now 1
    var stale = new State(new Phase.Idle(), 0L);
    assertThatThrownBy(() -> store.save(stale)).isInstanceOf(StaleStateException.class);
  }

  @Test
  void racingSaversProduceExactlyOneWinnerPerVersion() throws Exception {
    var store = new InMemoryAgentStateStore();
    int racers = 16;
    List<Callable<Boolean>> attempts = new ArrayList<>();
    for (int i = 0; i < racers; i++) {
      attempts.add(
          () -> {
            try {
              store.save(new State(new Phase.AwaitingModel(), 0L));
              return true;
            } catch (StaleStateException _) {
              return false;
            }
          });
    }
    List<Boolean> outcomes = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
      for (var future : pool.invokeAll(attempts)) {
        outcomes.add(future.get());
      }
    }
    assertThat(outcomes).isNotEmpty();
    assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1L);
    assertThat(store.load().version()).isEqualTo(1L);
  }

  @Test
  void aFreshScopeReportsItsBirthAsLastSaved() {
    var clock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
    var store = new InMemoryAgentStateStore(clock);
    assertThat(store.lastSaved()).isEqualTo(Instant.parse("2026-08-20T12:00:00Z"));
  }

  @Test
  void aSaveStampsTheClocksNow() {
    var birth = Instant.parse("2026-08-20T12:00:00Z");
    var later = Instant.parse("2026-08-20T12:05:00Z");
    var clock = new TestClock(birth);
    var store = new InMemoryAgentStateStore(clock);
    clock.set(later);
    store.save(new State(new Phase.AwaitingModel(), 0L));
    assertThat(store.lastSaved()).isEqualTo(later);
  }
}
