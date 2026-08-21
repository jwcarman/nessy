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

class InMemoryBacklogSubstrateTest {

  @Test
  void aNonPositiveCapacityIsRejected() {
    assertThatThrownBy(() -> new InMemoryBacklogSubstrate(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addBeyondCapacityThrowsTheRejection() {
    var substrate = new InMemoryBacklogSubstrate(2);
    var view = substrate.forScope("scope-a");
    view.add("a");
    view.add("b");
    assertThatThrownBy(() -> view.add("c"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("capacity 2");
  }

  @Test
  void pollingFreesCapacity() {
    var substrate = new InMemoryBacklogSubstrate(2);
    var view = substrate.forScope("scope-a");
    view.add("a");
    view.add("b");
    assertThat(view.poll()).contains("a");
    view.add("c");
    assertThat(view.poll()).contains("b");
    assertThat(view.poll()).contains("c");
    assertThat(view.poll()).isEmpty();
  }

  @Test
  void twoViewsOfTheSameIdShareTheQueue() {
    var substrate = new InMemoryBacklogSubstrate(2);
    var first = substrate.forScope("scope-a");
    var second = substrate.forScope("scope-a");
    first.add("a");
    assertThat(second.poll()).contains("a");
  }

  @Test
  void addingANullObservationIsRejected() {
    var substrate = new InMemoryBacklogSubstrate(2);
    var view = substrate.forScope("scope-a");
    assertThatThrownBy(() -> view.add(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("observation must not be null");
  }

  @Test
  void viewsOfDifferentIdsAreIsolated() {
    var substrate = new InMemoryBacklogSubstrate(2);
    var scopeA = substrate.forScope("scope-a");
    var scopeB = substrate.forScope("scope-b");
    scopeA.add("for a");
    assertThat(scopeB.poll()).isEmpty();
    assertThat(scopeA.poll()).contains("for a");
  }
}
