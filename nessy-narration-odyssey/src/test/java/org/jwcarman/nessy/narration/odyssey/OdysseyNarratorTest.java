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

  private final RecordingOdyssey odyssey = new RecordingOdyssey();
  private final OdysseyNarrator narrator =
      new OdysseyNarrator(new AgentStreams(odyssey, A_DAY), JsonMapper.builder().build());

  private RecordingOdyssey.Published only() {
    assertThat(odyssey.published).hasSize(1);
    return odyssey.published.getFirst();
  }

  private static JsonNode data(RecordingOdyssey.Published published) {
    assertThat(published.data()).isInstanceOf(JsonNode.class);
    return (JsonNode) published.data();
  }

  @Test
  @DisplayName("the stream is the agent's: its type and its id")
  void an_event_lands_on_the_stream_named_for_the_agent() {
    narrator.narrate(CHAT, ONE, new AgentEvent.Thinking());
    assertThat(only().stream()).isEqualTo("nessy/chat/" + ONE.value());
    assertThat(odyssey.lastTtl).isEqualTo(A_DAY);
  }

  @Test
  @DisplayName("the event name is the kind, and the data is the fields")
  void a_delta_is_its_text() {
    narrator.narrate(CHAT, ONE, new AgentEvent.ContentDelta("hel"));
    assertThat(only().eventName()).isEqualTo("content-delta");
    assertThat(data(only()).path("text").asString()).isEqualTo("hel");
  }

  @Test
  @DisplayName("value types cross as their bare values, as they do in the story")
  void ids_are_bare_values() {
    narrator.narrate(CHAT, ONE, new AgentEvent.TurnStarted(new TurnId(7), "hello"));
    JsonNode data = data(only());
    assertThat(data.path("turn").asLong()).isEqualTo(7);
    assertThat(data.path("observation").asString()).isEqualTo("hello");
  }

  @Test
  void a_denial_carries_its_call_and_reason() {
    narrator.narrate(CHAT, ONE, new AgentEvent.CallDenied(new CallId("c1"), "not tonight"));
    assertThat(only().eventName()).isEqualTo("call-denied");
    assertThat(data(only()).path("callId").asString()).isEqualTo("c1");
    assertThat(data(only()).path("reason").asString()).isEqualTo("not tonight");
  }

  @Test
  void a_request_for_actions_names_its_tools() {
    narrator.narrate(
        CHAT,
        ONE,
        new AgentEvent.ActionsRequested(List.of(new ToolName("df"), new ToolName("docker"))));
    JsonNode names = data(only()).path("toolNames");
    assertThat(names.isArray()).isTrue();
    assertThat(names.get(0).asString()).isEqualTo("df");
    assertThat(names.get(1).asString()).isEqualTo("docker");
  }

  @Test
  void a_deferral_carries_when_it_is_due() {
    Instant until = Instant.parse("2026-09-15T12:00:00Z");
    narrator.narrate(
        CHAT, ONE, new AgentEvent.CallDeferred(new CallId("c1"), new ToolName("slow"), until));
    assertThat(only().eventName()).isEqualTo("call-deferred");
    assertThat(data(only()).path("until").asString()).startsWith("2026-09-15T12:00:00");
  }

  @Test
  @DisplayName("an event with nothing to say is an empty object, not a missing one")
  void an_empty_event_is_an_empty_object() {
    narrator.narrate(CHAT, ONE, new AgentEvent.Terminated());
    assertThat(only().eventName()).isEqualTo("terminated");
    assertThat(data(only()).isObject()).isTrue();
    assertThat(data(only()).isEmpty()).isTrue();
  }

  @Test
  @DisplayName("every kind of event has a name, and no two share one")
  void every_event_kind_is_named_uniquely() {
    List<AgentEvent> all =
        List.of(
            new AgentEvent.TurnStarted(new TurnId(1), "x"),
            new AgentEvent.Thinking(),
            new AgentEvent.Answered("x"),
            new AgentEvent.TurnFailed(),
            new AgentEvent.TurnRefused(),
            new AgentEvent.Commentary("x"),
            new AgentEvent.ActionsRequested(List.of()),
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
    assertThat(all.stream().map(OdysseyNarrator::nameOf))
        .doesNotHaveDuplicates()
        .allSatisfy(name -> assertThat(name).matches("[a-z]+(-[a-z]+)*"));
  }
}
