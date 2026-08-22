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
package org.jwcarman.nessy.agent.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.RaceOnceOnWriteStore;
import org.jwcarman.nessy.spi.store.InMemoryScopedStore;
import org.jwcarman.nessy.spi.store.ScopedStore;

class StoredBacklogTest {

  @Test
  void aNonPositiveCapacityIsRejected() {
    ScopedStore store = new InMemoryScopedStore();
    assertThatThrownBy(() -> new StoredBacklog(store, "agent-a", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFreshBacklogPollsEmpty() {
    var backlog = new StoredBacklog(new InMemoryScopedStore(), "agent-a", 2);
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void addedObservationsPollInFifoOrder() {
    var backlog = new StoredBacklog(new InMemoryScopedStore(), "agent-a", 3);
    backlog.add("a");
    backlog.add("b");
    backlog.add("c");
    assertThat(backlog.poll()).contains("a");
    assertThat(backlog.poll()).contains("b");
    assertThat(backlog.poll()).contains("c");
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void addBeyondCapacityThrowsTheRejection() {
    var backlog = new StoredBacklog(new InMemoryScopedStore(), "agent-a", 2);
    backlog.add("a");
    backlog.add("b");
    assertThatThrownBy(() -> backlog.add("c"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("capacity 2");
  }

  @Test
  void pollingFreesCapacity() {
    var backlog = new StoredBacklog(new InMemoryScopedStore(), "agent-a", 2);
    backlog.add("a");
    backlog.add("b");
    assertThat(backlog.poll()).contains("a");
    backlog.add("c");
    assertThat(backlog.poll()).contains("b");
    assertThat(backlog.poll()).contains("c");
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void twoViewsOverOneKernelShareTheQueue() {
    ScopedStore store = new InMemoryScopedStore();
    var writer = new StoredBacklog(store, "agent-a", 4);
    var reader = new StoredBacklog(store, "agent-a", 4);

    writer.add("first");
    writer.add("second");

    assertThat(reader.poll()).contains("first");
    assertThat(reader.poll()).contains("second");
    assertThat(reader.poll()).isEmpty();
  }

  @Test
  void addRetriesAfterLosingAWriteConflictAndTheElementStillLands() {
    ScopedStore raceStore = new RaceOnceOnWriteStore(new InMemoryScopedStore(), "[\"raced-in\"]");
    var backlog = new StoredBacklog(raceStore, "agent-a", 2);

    backlog.add("mine");

    assertThat(backlog.poll()).contains("raced-in");
    assertThat(backlog.poll()).contains("mine");
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void pollRetriesAfterLosingAWriteConflictAndStillRemovesExactlyItsElement() {
    ScopedStore kernel = new InMemoryScopedStore();
    var seeded = new StoredBacklog(kernel, "agent-a", 3);
    seeded.add("a");
    seeded.add("b");

    ScopedStore raceStore = new RaceOnceOnWriteStore(kernel, "[\"a\",\"b\",\"c\"]");
    var backlog = new StoredBacklog(raceStore, "agent-a", 3);

    assertThat(backlog.poll()).contains("a");

    var reader = new StoredBacklog(kernel, "agent-a", 3);
    assertThat(reader.poll()).contains("b");
    assertThat(reader.poll()).contains("c");
    assertThat(reader.poll()).isEmpty();
  }
}
