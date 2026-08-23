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
package org.jwcarman.nessy.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.Remembrance;

class VerbatimMemoryTest {

  @Test
  void aFreshMemoryRecallsAnEmptyContext() {
    assertThat(new VerbatimMemory().recall().messages()).isEmpty();
  }

  @Test
  void rememberedMessagesRecallInOrder() {
    var memory = new VerbatimMemory();
    memory.remember(new Remembrance.UserMessage("turn-1", Message.user("first")));
    memory.remember(new Remembrance.UserMessage("turn-2", Message.user("second")));
    assertThat(memory.recall().messages())
        .containsExactly(Message.user("first"), Message.user("second"));
  }

  @Test
  void rememberingTheSameKeyTwiceConvergesToOneFact() {
    var memory = new VerbatimMemory();
    var remembrance = new Remembrance.UserMessage("turn-1", Message.user("only once"));
    memory.remember(remembrance);
    memory.remember(remembrance);
    assertThat(memory.recall().messages()).containsExactly(Message.user("only once"));
  }

  @Test
  void recallReturnsASnapshotNotALiveView() {
    var memory = new VerbatimMemory();
    memory.remember(new Remembrance.UserMessage("turn-1", Message.user("one")));
    List<Message> snapshot = memory.recall().messages();
    memory.remember(new Remembrance.UserMessage("turn-2", Message.user("two")));
    assertThat(snapshot).hasSize(1);
  }
}
