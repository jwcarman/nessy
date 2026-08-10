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
package org.jwcarman.nessy.spi.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.Message;

class MemoryTest {

  @Nested
  class None {

    @Test
    void none_recalls_nothing() {
      Memory memory = Memory.none();

      assertThat(memory.recall(Context.of(List.of(Message.user("hi"))))).isEmpty();
    }

    /**
     * A stable singleton, not a fresh lambda per call: {@code InProcessEngine} depends on this
     * identity so it can recognize the default, no-op memory by reference ({@code memory ==
     * Memory.NONE}) rather than by a heuristic, the same load-bearing trick {@code
     * CompactionTrigger.never()} uses.
     */
    @Test
    void none_is_the_same_instance_every_call() {
      assertThat(Memory.none()).isSameAs(Memory.none());
    }
  }

  @Nested
  class A_custom_memory {

    @Test
    void a_lambda_memory_recalls_whatever_it_is_given() {
      Message fact = Message.user("the sky is blue");
      Memory memory = context -> List.of(fact);

      assertThat(memory.recall(Context.of(List.of(Message.user("what color is the sky?")))))
          .containsExactly(fact);
    }
  }
}
