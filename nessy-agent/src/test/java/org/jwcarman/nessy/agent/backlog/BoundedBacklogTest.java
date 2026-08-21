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

class BoundedBacklogTest {

  @Test
  void aNonPositiveCapacityIsRejected() {
    assertThatThrownBy(() -> new BoundedBacklog<String>(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addBeyondCapacityThrowsTheRejection() {
    var backlog = new BoundedBacklog<String>(2);
    backlog.add("a");
    backlog.add("b");
    assertThatThrownBy(() -> backlog.add("c"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("capacity 2");
  }

  @Test
  void pollingFreesCapacity() {
    var backlog = new BoundedBacklog<String>(2);
    backlog.add("a");
    backlog.add("b");
    assertThat(backlog.poll()).contains("a");
    backlog.add("c");
    assertThat(backlog.poll()).contains("b");
    assertThat(backlog.poll()).contains("c");
    assertThat(backlog.poll()).isEmpty();
  }
}
