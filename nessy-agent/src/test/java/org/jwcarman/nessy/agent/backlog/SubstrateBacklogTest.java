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

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.RaceOnceOnWriteSubstrate;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class SubstrateBacklogTest {

  @Test
  void aNonPositiveCapacityIsRejected() {
    Substrate store = new InMemorySubstrate();
    assertThatThrownBy(() -> new SubstrateBacklog(store, "agent-a", 0, TestMappers.plainlyPinned()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFreshBacklogPollsEmpty() {
    var backlog =
        new SubstrateBacklog(new InMemorySubstrate(), "agent-a", 2, TestMappers.plainlyPinned());
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void addedObservationsPollInFifoOrder() {
    var backlog =
        new SubstrateBacklog(new InMemorySubstrate(), "agent-a", 3, TestMappers.plainlyPinned());
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
    var backlog =
        new SubstrateBacklog(new InMemorySubstrate(), "agent-a", 2, TestMappers.plainlyPinned());
    backlog.add("a");
    backlog.add("b");
    assertThatThrownBy(() -> backlog.add("c"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("capacity 2");
  }

  @Test
  void pollingFreesCapacity() {
    var backlog =
        new SubstrateBacklog(new InMemorySubstrate(), "agent-a", 2, TestMappers.plainlyPinned());
    backlog.add("a");
    backlog.add("b");
    assertThat(backlog.poll()).contains("a");
    backlog.add("c");
    assertThat(backlog.poll()).contains("b");
    assertThat(backlog.poll()).contains("c");
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void twoViewsOverOneSubstrateShareTheQueue() {
    Substrate store = new InMemorySubstrate();
    var writer = new SubstrateBacklog(store, "agent-a", 4, TestMappers.plainlyPinned());
    var reader = new SubstrateBacklog(store, "agent-a", 4, TestMappers.plainlyPinned());

    writer.add("first");
    writer.add("second");

    assertThat(reader.poll()).contains("first");
    assertThat(reader.poll()).contains("second");
    assertThat(reader.poll()).isEmpty();
  }

  @Test
  void addRetriesAfterLosingAWriteConflictAndTheElementStillLands() {
    Substrate raceStore =
        new RaceOnceOnWriteSubstrate(
            new InMemorySubstrate(), "[\"raced-in\"]".getBytes(StandardCharsets.UTF_8));
    var backlog = new SubstrateBacklog(raceStore, "agent-a", 2, TestMappers.plainlyPinned());

    backlog.add("mine");

    assertThat(backlog.poll()).contains("raced-in");
    assertThat(backlog.poll()).contains("mine");
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void pollRetriesAfterLosingAWriteConflictAndStillRemovesExactlyItsElement() {
    Substrate substrate = new InMemorySubstrate();
    var seeded = new SubstrateBacklog(substrate, "agent-a", 3, TestMappers.plainlyPinned());
    seeded.add("a");
    seeded.add("b");

    Substrate raceStore =
        new RaceOnceOnWriteSubstrate(
            substrate, "[\"a\",\"b\",\"c\"]".getBytes(StandardCharsets.UTF_8));
    var backlog = new SubstrateBacklog(raceStore, "agent-a", 3, TestMappers.plainlyPinned());

    assertThat(backlog.poll()).contains("a");

    var reader = new SubstrateBacklog(substrate, "agent-a", 3, TestMappers.plainlyPinned());
    assertThat(reader.poll()).contains("b");
    assertThat(reader.poll()).contains("c");
    assertThat(reader.poll()).isEmpty();
  }
}
