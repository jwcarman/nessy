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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.backlog.BacklogItem;

/**
 * The proof that erasure was handled rather than hoped about.
 *
 * <p>An {@code AgentState<O>} comes back off a mailbox or out of a database as an {@code
 * AgentState}, and Jackson would rebuild its backlog as maps. Everything else in this engine
 * depends on that not happening.
 */
@DisplayName("Agent state, written and read back")
class StateSerializerTest {

  /** Someone's observation vocabulary. Nothing about it is known to the engine. */
  record HouseEvent(String room, String what) {}

  private static final AgentType WATCHMAN = AgentType.of("watchman");

  private static StateTypes typesKnowing(Class<?> observationType) {
    StateTypes types = new StateTypes();
    types.register(WATCHMAN, observationType);
    return types;
  }

  private static AgentState<HouseEvent> stateWithOneObservation() {
    return new AgentState<>(
        WATCHMAN,
        List.of(new BacklogItem<>("b1", new HouseEvent("kitchen", "door opened"), Instant.EPOCH)),
        null,
        null);
  }

  @Test
  void the_observation_comes_back_as_itself_not_as_a_map() {
    StateSerializer serializer = new StateSerializer(typesKnowing(HouseEvent.class));
    AgentState<HouseEvent> written = stateWithOneObservation();

    byte[] bytes = serializer.toBinary(written);
    Object read = serializer.fromBinary(bytes, serializer.manifest(written));

    assertThat(read).isInstanceOf(AgentState.class);
    AgentState<?> state = (AgentState<?>) read;
    assertThat(state.backlog()).hasSize(1);
    assertThat(state.backlog().getFirst().observation())
        .isInstanceOf(HouseEvent.class)
        .isEqualTo(new HouseEvent("kitchen", "door opened"));
  }

  @Test
  @DisplayName("without the registered type it WOULD be a map — the test is not vacuous")
  void jackson_alone_loses_the_observation_type() throws Exception {
    StateSerializer serializer = new StateSerializer(typesKnowing(HouseEvent.class));
    byte[] bytes = serializer.toBinary(stateWithOneObservation());

    AgentState<?> naive = EngineMapper.create().readValue(bytes, AgentState.class);

    assertThat(naive.backlog().getFirst().observation()).isInstanceOf(Map.class);
  }

  @Test
  void the_manifest_names_the_agent_type() {
    StateSerializer serializer = new StateSerializer(typesKnowing(HouseEvent.class));

    assertThat(serializer.manifest(stateWithOneObservation())).isEqualTo("agent-state-v1:watchman");
  }

  @Test
  void an_idle_state_round_trips_even_with_nothing_to_infer_a_type_from() {
    StateSerializer serializer = new StateSerializer(typesKnowing(HouseEvent.class));
    AgentState<HouseEvent> idle = AgentState.idle(WATCHMAN);

    Object read = serializer.fromBinary(serializer.toBinary(idle), serializer.manifest(idle));

    assertThat(read).isEqualTo(idle);
  }

  @Test
  void an_unregistered_agent_type_says_so_rather_than_guessing() {
    StateSerializer serializer = new StateSerializer(new StateTypes());
    byte[] bytes =
        new StateSerializer(typesKnowing(HouseEvent.class)).toBinary(AgentState.idle(WATCHMAN));

    assertThatThrownBy(() -> serializer.fromBinary(bytes, "agent-state-v1:watchman"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("a harness this process never created");
  }

  @Test
  void a_manifest_it_does_not_recognise_is_refused() {
    StateSerializer serializer = new StateSerializer(typesKnowing(HouseEvent.class));

    assertThatThrownBy(() -> serializer.fromBinary(new byte[0], "something-else"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown state manifest");
  }
}
