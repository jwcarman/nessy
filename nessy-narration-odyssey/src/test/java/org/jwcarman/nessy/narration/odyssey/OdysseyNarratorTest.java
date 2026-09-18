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
package org.jwcarman.nessy.narration.odyssey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.odyssey.core.TtlPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Narrating to an agent's stream")
class OdysseyNarratorTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId ONE = new AgentId(UUID.randomUUID());
  private static final TtlPolicy A_DAY =
      new TtlPolicy(Duration.ofDays(1), Duration.ofDays(1), Duration.ofHours(1));

  private static final List<AgentEvent> EVERY_KIND =
      List.of(
          new AgentEvent.TurnStarted(new TurnId(1), "x"),
          new AgentEvent.Thinking(),
          new AgentEvent.Answered("x"),
          new AgentEvent.TurnEnded(new TurnId(1)),
          new AgentEvent.TurnFailed(),
          new AgentEvent.TurnRefused(),
          new AgentEvent.Commentary("x"),
          new AgentEvent.ActionsRequested(List.of(new ToolName("t"))),
          new AgentEvent.CallApproved(new CallId("c")),
          new AgentEvent.CallDenied(new CallId("c"), "r"),
          new AgentEvent.CallFinished(new CallId("c")),
          new AgentEvent.CallFailed(new CallId("c"), "m"),
          new AgentEvent.Terminated(),
          new AgentEvent.ApprovalSought(new CallId("c"), "a"),
          new AgentEvent.ApprovalDeferred(new CallId("c"), "a", Instant.EPOCH),
          new AgentEvent.CallDeferred(new CallId("c"), new ToolName("t"), Instant.EPOCH),
          new AgentEvent.ThinkingDelta("x"),
          new AgentEvent.ContentDelta("x"));

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final RecordingOdyssey odyssey = new RecordingOdyssey();
  private final OdysseyNarrator narrator = new OdysseyNarrator(new AgentStreams(odyssey, A_DAY));

  private RecordingOdyssey.Published only() {
    assertThat(odyssey.published).hasSize(1);
    return odyssey.published.getFirst();
  }

  @Test
  @DisplayName("the stream is the agent's: its type and its id, and it carries events")
  void an_event_lands_on_the_stream_named_for_the_agent() {
    narrator.on(CHAT, ONE, new AgentEvent.Thinking());
    assertThat(only().stream()).isEqualTo("nessy/chat/" + ONE.value());
    assertThat(only().type()).isEqualTo(AgentEvent.class);
    assertThat(only().data()).isEqualTo(new AgentEvent.Thinking());
    assertThat(odyssey.lastTtl).isEqualTo(A_DAY);
  }

  @Test
  @DisplayName("the event name is the kind, and the event goes as it is")
  void a_delta_is_published_under_its_kind() {
    AgentEvent.ContentDelta delta = new AgentEvent.ContentDelta("hel");
    narrator.on(CHAT, ONE, delta);
    assertThat(only().eventName()).isEqualTo("content-delta");
    assertThat(only().data()).isEqualTo(delta);
  }

  @Test
  @DisplayName("on the wire an event names its kind, and its ids are bare values")
  void the_json_of_an_event_carries_its_kind() {
    JsonNode json = mapper.valueToTree(new AgentEvent.TurnStarted(new TurnId(7), "hello"));
    assertThat(json.path("type").asString()).isEqualTo("turn-started");
    assertThat(json.path("turn").asLong()).isEqualTo(7);
    assertThat(json.path("observation").asString()).isEqualTo("hello");
    JsonNode denied = mapper.valueToTree(new AgentEvent.CallDenied(new CallId("c1"), "no"));
    assertThat(denied.path("callId").asString()).isEqualTo("c1");
  }

  @Test
  @DisplayName("and every kind reads back as what it was, which is what resuming a stream needs")
  void every_kind_round_trips_through_json() {
    for (AgentEvent event : EVERY_KIND) {
      String json = mapper.writeValueAsString(event);
      assertThat(mapper.readValue(json, AgentEvent.class)).as(json).isEqualTo(event);
    }
  }

  @Test
  @DisplayName("the SSE event name and the kind in the JSON are one name")
  void the_wire_name_is_the_declared_kind() {
    for (AgentEvent event : EVERY_KIND) {
      assertThat(OdysseyNarrator.nameOf(event))
          .isEqualTo(mapper.valueToTree(event).path("type").asString());
    }
    assertThat(EVERY_KIND.stream().map(OdysseyNarrator::nameOf)).doesNotHaveDuplicates();
  }
}
