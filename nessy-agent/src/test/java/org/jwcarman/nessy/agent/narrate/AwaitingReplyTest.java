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
package org.jwcarman.nessy.agent.narrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnEvent;

class AwaitingReplyTest {

  @Test
  void theLastAssistantTextIsTheReply() {
    var waiter = new AwaitingReply();
    waiter.on(new TurnEvent.AssistantSaid(Message.assistant(List.of(new TextBlock("hello back")))));
    waiter.on(new TurnEvent.TurnEnded(null));
    assertThat(waiter.await(Duration.ofSeconds(1))).isEqualTo("hello back");
  }

  @Test
  void aFailedTurnThrowsWithItsReason() {
    var waiter = new AwaitingReply();
    waiter.on(new TurnEvent.TurnEnded("overloaded"));
    var timeout = Duration.ofSeconds(1);
    assertThatThrownBy(() -> waiter.await(timeout))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("overloaded");
  }

  @Test
  void aTurnThatNeverEndsTimesOut() {
    var waiter = new AwaitingReply();
    var timeout = Duration.ofMillis(50);
    assertThatThrownBy(() -> waiter.await(timeout))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timed out");
  }
}
