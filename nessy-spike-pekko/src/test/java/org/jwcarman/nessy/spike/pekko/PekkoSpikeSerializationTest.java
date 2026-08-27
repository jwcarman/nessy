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

import com.typesafe.config.ConfigFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Adapter;
import org.apache.pekko.serialization.Serialization;
import org.apache.pekko.serialization.SerializationExtension;
import org.apache.pekko.serialization.Serializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THROWAWAY SPIKE -- PHASE 1. What Pekko will actually write.
 *
 * <p>This test exists because of a trap: {@code PersistenceTestKitDurableStateStore} keeps the
 * state OBJECT in a map and never serialises it, so a green phase-1 flow test proves nothing at all
 * about the bytes. Anything the wire form gets wrong would surface for the first time in phase 2,
 * against Postgres. So phase 1 asks Pekko's serialiser directly.
 */
@DisplayName("What Pekko writes for a parked turn")
class PekkoSpikeSerializationTest {

  private static SpikeCluster cluster;
  private static Serialization serialization;

  @BeforeAll
  static void start() {
    cluster =
        new SpikeCluster(
            ConfigFactory.load("spike-inmemory").resolve(), java.time.Duration.ofMillis(1));
    ActorSystem<Void> system = cluster.system();
    serialization = SerializationExtension.get(Adapter.toClassic(system));
  }

  @AfterAll
  static void stop() {
    cluster.close();
  }

  private static SpikeTurnState.WorkingTools parkedTurn() {
    return new SpikeTurnState.WorkingTools(
        List.of("user: tidy up", "assistant: (asked for [clock, delete])"),
        List.of(
            new SpikeToolCall("call-1", "clock", "now", new SpikeCallPhase.Finished("12:00")),
            new SpikeToolCall(
                "call-2",
                "delete",
                "/tmp/everything",
                new SpikeCallPhase.AwaitingApproval("may I run delete on /tmp/everything?"))));
  }

  @Test
  void the_state_is_bound_to_the_jackson_json_serializer_by_the_marker_interface_alone() {
    Serializer serializer = serialization.findSerializerFor(parkedTurn());

    assertThat(serializer.getClass().getName()).contains("JacksonJsonSerializer");
  }

  @Test
  void our_id_name_discriminators_are_what_lands_on_the_wire() {
    SpikeTurnState.WorkingTools parked = parkedTurn();

    byte[] bytes = serialization.serialize(parked).get();
    String json = new String(bytes, StandardCharsets.UTF_8);

    // Printed on purpose: this string is the deliverable, not the assertion.
    System.out.println("[spike] durable-state bytes: " + json);

    assertThat(json).isNotEmpty();
    assertThat(json)
        .contains("\"state\":\"working-tools\"")
        .contains("\"phase\":\"finished\"")
        .contains("\"phase\":\"awaiting-approval\"")
        // Pekko forbids Id.CLASS; nothing here should be naming a Java class.
        .doesNotContain("org.jwcarman.nessy.spike.pekko");
  }

  @Test
  void a_parked_turn_survives_a_full_round_trip_through_pekkos_serializer() {
    SpikeTurnState.WorkingTools parked = parkedTurn();
    Serializer serializer = serialization.findSerializerFor(parked);

    byte[] bytes = serialization.serialize(parked).get();
    Object back =
        serialization
            .deserialize(
                bytes, serializer.identifier(), SpikeTurnState.WorkingTools.class.getName())
            .get();

    assertThat(back).isEqualTo(parked);
  }
}
