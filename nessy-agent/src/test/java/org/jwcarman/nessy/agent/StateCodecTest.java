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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class StateCodecTest {

  private final StateCodec codec = new StateCodec();

  @Test
  void anIdleStateRoundTrips() {
    var state = State.initial();
    assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
  }

  @Test
  void anAwaitingModelStateRoundTrips() {
    var state = new State(new Phase.AwaitingModel(), 7L);
    assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
  }

  @Test
  void anAwaitingToolsStateRoundTripsWithSignaturesIntact() {
    var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
    var turn =
        Message.assistant(List.<ContentBlock>of(new ToolUseBlock(call, "gemini-thought-sig")));
    var state =
        new State(
            new Phase.AwaitingTools(
                turn, Set.of("a"), List.of(new ToolResultBlock("z", "done", false))),
            42L);
    assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
  }

  @Test
  void theDiscriminatorPropertyIsCalledPhase() {
    assertThat(codec.encode(State.initial())).contains("\"phase\":\"IDLE\"");
  }

  @Test
  void anUnknownDiscriminatorFailsLoudly() {
    var codecUnderTest = codec;
    assertThatThrownBy(
            () -> codecUnderTest.decode("{\"phase\":\"AWAITING_APPROVAL\",\"version\":1}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aStateRejectsANegativeVersion() {
    var idle = new Phase.Idle();
    assertThatThrownBy(() -> new State(idle, -1L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aStateRejectsANullPhase() {
    assertThatThrownBy(() -> new State(null, 0L)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void anUnreadablePayloadFailsLoudly() {
    var codecUnderTest = codec;
    assertThatThrownBy(() -> codecUnderTest.decode("not json at all"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
