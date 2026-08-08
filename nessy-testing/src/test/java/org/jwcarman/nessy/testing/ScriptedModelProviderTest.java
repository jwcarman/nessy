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
import org.jwcarman.nessy.core.Message;
import org.jwcarman.nessy.core.StopReason;
import org.jwcarman.nessy.model.ModelEvent;
import org.jwcarman.nessy.model.ModelRequest;
import org.jwcarman.nessy.model.ModelStream;

class ScriptedModelProviderTest {

  private static ModelRequest request() {
    return new ModelRequest(
        List.of(Message.user("hi")), "system", "fake-model", 1024, List.of(), Set.of());
  }

  private static List<ModelEvent> drain(ModelStream stream) {
    List<ModelEvent> events = new ArrayList<>();
    try (ModelStream open = stream) {
      open.forEach(events::add);
    }
    return events;
  }

  @Test
  void replaysASingleTextTurn() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hello").endTurn().build();

    List<ModelEvent> events = drain(provider.stream(request()));

    assertThat(events)
        .containsExactly(
            new ModelEvent.TextChunk("Hello"), new ModelEvent.TurnEnded(StopReason.END_TURN));
  }

  @Test
  void replaysTurnsInOrder() {
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
            new ModelEvent.TextChunk("Done"), new ModelEvent.TurnEnded(StopReason.END_TURN));
  }

  @Test
  void recordsEveryRequestItReceived() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hello").endTurn().build();

    provider.stream(request()).close();

    assertThat(provider.requests()).hasSize(1);
    assertThat(provider.requests().getFirst().model()).isEqualTo("fake-model");
  }

  @Test
  void runningOutOfScriptIsALoudFailure() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hello").endTurn().build();
    provider.stream(request()).close();

    assertThatThrownBy(() -> provider.stream(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("script exhausted");
  }
}
