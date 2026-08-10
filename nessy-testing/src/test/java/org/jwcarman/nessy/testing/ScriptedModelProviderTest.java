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
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

class ScriptedModelProviderTest {

  private static ModelRequest request() {
    return new ModelRequest(
        Context.of(List.of(Message.user("hi"))),
        "system",
        "fake-model",
        1024,
        List.of(),
        Set.of(),
        null);
  }

  private static List<ModelEvent> drain(ModelStream stream) {
    List<ModelEvent> events = new ArrayList<>();
    try (ModelStream open = stream) {
      open.forEach(events::add);
    }
    return events;
  }

  @Test
  void replays_a_single_text_turn() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hello").endTurn().build();

    List<ModelEvent> events = drain(provider.stream(request()));

    assertThat(events)
        .containsExactly(
            new ModelEvent.TextChunk("Hello"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
  }

  @Test
  void replays_turns_in_order() {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUse("c1", "read_file", args)
            .endWithToolUse()
            .text("Done")
            .endTurn()
            .build();

    assertThat(drain(provider.stream(request()))).hasSize(2);
    assertThat(drain(provider.stream(request())))
        .containsExactly(
            new ModelEvent.TextChunk("Done"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
  }

  @Test
  void records_every_request_it_received() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hello").endTurn().build();

    provider.stream(request()).close();

    assertThat(provider.requests()).hasSize(1);
    assertThat(provider.requests().getFirst().model()).isEqualTo("fake-model");
  }

  @Test
  void iterating_the_same_stream_twice_is_a_loud_failure() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hello").endTurn().build();

    try (ModelStream stream = provider.stream(request())) {
      stream.iterator();
      assertThatThrownBy(stream::iterator)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already been iterated");
    }
  }

  @Test
  void requests_is_a_snapshot_rather_than_a_live_view() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hello").endTurn().text("Again").endTurn().build();
    provider.stream(request()).close();
    List<ModelRequest> snapshot = provider.requests();

    provider.stream(request()).close();

    assertThat(snapshot).hasSize(1);
    assertThat(provider.requests()).hasSize(2);
  }

  @Test
  void running_out_of_script_is_a_loud_failure() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hello").endTurn().build();
    provider.stream(request()).close();

    assertThatThrownBy(() -> provider.stream(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("script exhausted");
  }
}
