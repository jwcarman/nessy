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
package org.jwcarman.nessy.spike.pekko;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THROWAWAY SPIKE. What we write, now that the codec is ours.
 *
 * <p>No ActorSystem is needed to ask this question any more. Rounds 1 and 2 had to boot one,
 * because the only way to reach Pekko's Jackson was through {@code SerializationExtension}. Our
 * serializer is an ordinary object, so the wire form is testable like any other code — which is
 * itself part of the argument for owning it.
 */
@DisplayName("What the spike writes for a parked turn")
class SpikeStateSerializerTest {

  private final SpikeStateSerializer codec = new SpikeStateSerializer();

  private static SpikeTurnState.WorkingTools parkedTurn() {
    return new SpikeTurnState.WorkingTools(
        List.of("user: tidy up", "assistant: (asked for [clock, delete])"),
        List.of(
            new SpikeToolCall("call-1", "clock", "now", "12:00"),
            SpikeToolCall.asked("call-2", "delete", "/tmp/everything")));
  }

  @Test
  void the_manifest_is_a_stable_name_we_chose_and_never_a_java_class_name() {
    String manifest = codec.manifest(parkedTurn());

    assertThat(manifest).isEqualTo("spike-turn-state-v1");
    assertThat(manifest).doesNotContain("org.jwcarman");
  }

  @Test
  void our_id_name_discriminators_are_what_lands_on_the_wire() {
    byte[] bytes = codec.toBinary(parkedTurn());
    String json = new String(bytes, StandardCharsets.UTF_8);

    System.out.println("[spike] durable-state bytes: " + json);

    assertThat(json).isNotEmpty();
    assertThat(json)
        .contains("\"state\":\"working-tools\"")
        .contains("\"outcome\":\"12:00\"")
        .doesNotContain("org.jwcarman.nessy.spike.pekko");
  }

  @Test
  void a_parked_turn_survives_a_full_round_trip() {
    SpikeTurnState.WorkingTools parked = parkedTurn();

    Object back = codec.fromBinary(codec.toBinary(parked), SpikeStateSerializer.TURN_STATE_V1);

    assertThat(back).isEqualTo(parked);
  }

  @Test
  void an_unknown_manifest_fails_loudly_rather_than_guessing() {
    byte[] bytes = codec.toBinary(parkedTurn());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> codec.fromBinary(bytes, "spike-turn-state-v99"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown manifest");
  }
}
