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
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.SessionState;

class MemoryTest {

  @Nested
  class A_custom_memory {

    @Test
    void a_lambda_memory_recalls_whatever_it_is_given() {
      Message fact = Message.user("the sky is blue");
      Memory memory = state -> List.of(fact);
      SessionState state =
          SessionState.newSession(SessionId.generate())
              .withMessages(List.of(Message.user("what color is the sky?")));

      assertThat(memory.recall(state)).containsExactly(fact);
    }
  }
}
