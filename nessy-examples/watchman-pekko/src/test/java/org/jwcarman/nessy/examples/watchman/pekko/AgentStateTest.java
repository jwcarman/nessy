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
package org.jwcarman.nessy.examples.watchman.pekko;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The document an agent persists")
class AgentStateTest {

  @Test
  void an_idle_agent_holds_no_turn() {
    AgentState state = AgentState.idle();

    assertThat(state.phase()).isInstanceOf(Phase.Idle.class);
    assertThat(state.turnId()).isNull();
  }

  @Test
  void starting_a_turn_names_it() {
    AgentState state = AgentState.idle().startingTurn("turn-1");

    assertThat(state.turnId()).isEqualTo("turn-1");
  }

  @Test
  void the_serialised_form_round_trips_through_the_state_serializer() {
    AgentState before =
        AgentState.idle().startingTurn("turn-1").withPhase(new Phase.CallingModel());

    StateSerializer codec = new StateSerializer();
    Object after = codec.fromBinary(codec.toBinary(before), StateSerializer.AGENT_STATE_V2);

    assertThat(after).isEqualTo(before);
  }
}
