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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.FailureKind;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.odyssey.core.TtlPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Narrating to an agent's stream")
class OdysseyNarratorTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId ONE = new AgentId(UUID.randomUUID());
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final TtlPolicy A_DAY =
      new TtlPolicy(Duration.ofDays(1), Duration.ofDays(1), Duration.ofHours(1));

  private static final List<Narration> EVERY_KIND =
      List.of(
          new Narration.TurnStarted(new TurnId(1)),
          new Narration.Thinking(),
          new Narration.Answered(new TurnId(1), Usage.unreported()),
          new Narration.TurnStopped(new TurnId(1), "too many calls"),
          new Narration.TurnFailed(
              new TurnId(1), FailureKind.PERMANENT, "the provider gave up", Usage.unreported()),
          new Narration.TurnRefused(new TurnId(1), "safety", Usage.unreported()),
          new Narration.InferenceRetried(
              new TurnId(1), FailureKind.TRANSIENT, "busy", Usage.unreported()),
          new Narration.Commentary("x"),
          new Narration.ActionsRequested(
              new TurnId(1),
              List.of(
                  new Narration.ActionsRequested.Call(
                      new CallId("c"), KEY, new ToolName("t"), "do t")),
              Usage.unreported()),
          new Narration.CallApproved(new CallId("c"), KEY, Optional.empty()),
          new Narration.CallDenied(new CallId("c"), KEY, "r", Optional.empty()),
          new Narration.CallFinished(new CallId("c"), KEY),
          new Narration.CallFailed(new CallId("c"), KEY, "m"),
          new Narration.Terminated(),
          new Narration.ApprovalSought(new CallId("c"), "a"),
          new Narration.ApprovalDeferred(new CallId("c"), "a", Instant.EPOCH),
          new Narration.CallDeferred(new CallId("c"), new ToolName("t"), Instant.EPOCH),
          new Narration.ThinkingDelta("x"),
          new Narration.ContentDelta("x"));

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
    narrator.on(Narrated.live(CHAT, ONE, new Narration.Thinking()));
    assertThat(only().stream()).isEqualTo("nessy/chat/" + ONE.value());
    assertThat(only().type()).isEqualTo(Narration.class);
    assertThat(only().data()).isEqualTo(new Narration.Thinking());
    assertThat(odyssey.lastTtl).isEqualTo(A_DAY);
  }

  @Test
  @DisplayName("the event name is the kind, and the event goes as it is")
  void a_delta_is_published_under_its_kind() {
    Narration.ContentDelta delta = new Narration.ContentDelta("hel");
    narrator.on(Narrated.live(CHAT, ONE, delta));
    assertThat(only().eventName()).isEqualTo("content-delta");
    assertThat(only().data()).isEqualTo(delta);
  }

  @Test
  @DisplayName("on the wire an event names its kind, and its ids are bare values")
  void the_json_of_an_event_carries_its_kind() {
    JsonNode json = mapper.valueToTree(new Narration.TurnStarted(new TurnId(7)));
    assertThat(json.path("type").asString()).isEqualTo("turn-started");
    assertThat(json.path("turn").asLong()).isEqualTo(7);
    assertThat(json.has("input"))
        .as("narration names what happened; it does not carry the words it happened to")
        .isFalse();
    JsonNode denied =
        mapper.valueToTree(new Narration.CallDenied(new CallId("c1"), KEY, "no", Optional.empty()));
    assertThat(denied.path("callId").asString()).isEqualTo("c1");
  }

  @Test
  @DisplayName("and every kind reads back as what it was, which is what resuming a stream needs")
  void every_kind_round_trips_through_json() {
    for (Narration event : EVERY_KIND) {
      String json = mapper.writeValueAsString(event);
      assertThat(mapper.readValue(json, Narration.class)).as(json).isEqualTo(event);
    }
  }

  @Test
  @DisplayName("the SSE event name and the kind in the JSON are one name")
  void the_wire_name_is_the_declared_kind() {
    for (Narration event : EVERY_KIND) {
      assertThat(OdysseyNarrator.nameOf(event))
          .isEqualTo(mapper.valueToTree(event).path("type").asString());
    }
    assertThat(EVERY_KIND.stream().map(OdysseyNarrator::nameOf)).doesNotHaveDuplicates();
  }
}
