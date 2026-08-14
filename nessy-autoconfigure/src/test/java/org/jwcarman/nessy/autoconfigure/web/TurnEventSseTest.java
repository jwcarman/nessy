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
package org.jwcarman.nessy.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.autoconfigure.web.TurnEventSse.Event;

class TurnEventSseTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
  private static final ParkToken TOKEN = ParkToken.generate();

  private static List<TurnEvent> oneOfEveryVariant() {
    return List.of(
        new TurnEvent.TextDelta("prose"),
        new TurnEvent.ThinkingDelta("hmm"),
        new TurnEvent.RedactedThinking("opaque"),
        new TurnEvent.ToolCallRequested(CALL),
        new TurnEvent.ToolCallDecided(CALL, Decision.allow()),
        new TurnEvent.ToolCallCompleted(CALL, ToolResult.ok("done")),
        new TurnEvent.ToolCallProgressed(CALL, "halfway"),
        new TurnEvent.ToolCallParked(CALL, TOKEN));
  }

  @Nested
  class Of {

    @Test
    void every_variant_maps_to_its_named_payload() {
      List<TurnEvent> variants = oneOfEveryVariant();
      List<Event> mapped = variants.stream().map(TurnEventSse::of).toList();

      assertThat(mapped.subList(0, mapped.size() - 1))
          .containsExactly(
              new Event("delta", Map.of("text", "prose")),
              new Event("thinking", Map.of("text", "hmm")),
              new Event("thinking", Map.of("text", "[redacted]")),
              new Event("tool-requested", Map.of("id", "c1", "name", "issue_coupon")),
              new Event("tool-decided", Map.of("id", "c1", "allowed", true)),
              new Event("tool-completed", Map.of("id", "c1", "error", false)),
              new Event("tool-progress", Map.of("id", "c1", "message", "halfway")));
      // The last variant, ToolCallParked, has its own dedicated coverage below — its payload
      // is not a fixed literal (args is a pretty-printed rendering of the call's arguments).
      assertThat(mapped.get(mapped.size() - 1).name()).isEqualTo("tool-parked");
    }

    @Test
    void a_denied_decision_maps_with_its_verdict() {
      assertThat(TurnEventSse.of(new TurnEvent.ToolCallDecided(CALL, new Decision.Deny("no"))))
          .isEqualTo(new Event("tool-decided", Map.of("id", "c1", "allowed", false)));
    }

    @Test
    void an_errored_completion_maps_with_its_verdict() {
      assertThat(TurnEventSse.of(new TurnEvent.ToolCallCompleted(CALL, ToolResult.error("boom"))))
          .isEqualTo(new Event("tool-completed", Map.of("id", "c1", "error", true)));
    }

    @Test
    void a_park_is_named_tool_parked() {
      assertThat(TurnEventSse.of(new TurnEvent.ToolCallParked(CALL, TOKEN)).name())
          .isEqualTo("tool-parked");
    }

    @Test
    void a_park_s_payload_carries_the_token_tool_and_pretty_printed_args() {
      ObjectNode args = JsonNodeFactory.instance.objectNode();
      args.put("coupon", "SAVE10");
      ToolCall callWithArgs = new ToolCall("c1", "issue_coupon", args);

      Map<String, Object> payload =
          TurnEventSse.of(new TurnEvent.ToolCallParked(callWithArgs, TOKEN)).payload();

      assertThat(payload)
          .containsEntry("token", TOKEN.value())
          .containsEntry("tool", "issue_coupon");
      assertThat(payload.get("args")).isInstanceOf(String.class);
      assertThat((String) payload.get("args")).contains("\n");
    }
  }

  @Nested
  class Observer {

    @Test
    void the_observer_maps_every_event_through_of_into_the_sink() {
      List<Event> sunk = new ArrayList<>();
      Consumer<Event> sink = sunk::add;
      TurnObserver observer = TurnEventSse.observer(sink);

      List<TurnEvent> variants = oneOfEveryVariant();
      variants.forEach(observer::on);

      List<Event> expected = variants.stream().map(TurnEventSse::of).toList();
      assertThat(sunk).containsExactlyElementsOf(expected);
    }
  }
}
