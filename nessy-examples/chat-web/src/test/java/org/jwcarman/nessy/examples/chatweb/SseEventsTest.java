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
package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;

class SseEventsTest {

  @Test
  void every_turn_event_maps_to_a_named_payload() {
    ToolCall call = new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
    assertThat(SseEvents.of(new TurnEvent.TextDelta("hi")))
        .isEqualTo(new SseEvents.Event("delta", Map.of("text", "hi")));
    assertThat(SseEvents.of(new TurnEvent.ThinkingDelta("hmm")))
        .isEqualTo(new SseEvents.Event("thinking", Map.of("text", "hmm")));
    assertThat(SseEvents.of(new TurnEvent.ToolCallRequested(call)))
        .isEqualTo(
            new SseEvents.Event("tool-requested", Map.of("id", "c1", "name", "issue_coupon")));
    assertThat(SseEvents.of(new TurnEvent.ToolCallProgressed(call, "issuing…")))
        .isEqualTo(new SseEvents.Event("tool-progress", Map.of("id", "c1", "message", "issuing…")));
    assertThat(SseEvents.of(new TurnEvent.ToolCallCompleted(call, ToolResult.ok("done"))))
        .isEqualTo(new SseEvents.Event("tool-completed", Map.of("id", "c1", "error", false)));
  }

  @Test
  void a_decision_maps_with_its_verdict() {
    ToolCall call = new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
    assertThat(SseEvents.of(new TurnEvent.ToolCallDecided(call, Decision.allow())))
        .isEqualTo(new SseEvents.Event("tool-decided", Map.of("id", "c1", "allowed", true)));
  }

  @Test
  void redacted_thinking_is_a_marker_only() {
    assertThat(SseEvents.of(new TurnEvent.RedactedThinking("opaque")))
        .isEqualTo(new SseEvents.Event("thinking", Map.of("text", "[redacted]")));
  }
}
