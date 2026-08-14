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

import java.util.Map;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * Turns nessy's {@link TurnEvent} narration into named SSE payloads the browser understands, and a
 * {@link TurnObserver} that streams them to a sink.
 *
 * <p>The switch in {@link #of(TurnEvent)} is deliberately exhaustive with no {@code default} arm,
 * against the sealed-grammar etiquette's own advice for extender code ({@link TurnEvent}'s javadoc
 * recommends one, for forward tolerance across majors). This module compiles in the same reactor as
 * {@code nessy-core} rather than consuming it as an external artifact across a major boundary, so
 * the etiquette's core rule — fail loud at compile time when the grammar grows — is the better
 * trade here: a new {@code TurnEvent} variant should not silently vanish from the UI.
 */
public final class SseEvents {

  private SseEvents() {}

  /** One named SSE payload: {@code event.name()} on the wire, {@code payload} as its JSON data. */
  public record Event(String name, Map<String, Object> payload) {}

  /** Maps one {@link TurnEvent} to the named payload spec §4's endpoint table promises. */
  public static Event of(TurnEvent event) {
    return switch (event) {
      case TurnEvent.TextDelta e -> new Event("delta", Map.of("text", e.text()));
      case TurnEvent.ThinkingDelta e -> new Event("thinking", Map.of("text", e.text()));
      case TurnEvent.RedactedThinking e -> new Event("thinking", Map.of("text", "[redacted]"));
      case TurnEvent.ToolCallRequested e ->
          new Event("tool-requested", Map.of("id", e.call().id(), "name", e.call().name()));
      case TurnEvent.ToolCallProgressed e ->
          new Event("tool-progress", Map.of("id", e.call().id(), "message", e.message()));
      case TurnEvent.ToolCallDecided e ->
          new Event("tool-decided", Map.of("id", e.call().id(), "allowed", allowed(e.decision())));
      case TurnEvent.ToolCallCompleted e ->
          new Event("tool-completed", Map.of("id", e.call().id(), "error", e.result().isError()));
      case TurnEvent.ToolCallParked e ->
          // The live card source (Task 6): the observed park event carries the full
          // {token, tool, args} shape ChatController.get's snapshot-rebuild path also produces via
          // #approvalCard, so a losing concurrent driver's zero-emission race still redraws the
          // identical card on the next page load.
          new Event("approval-needed", approvalCard(new ParkedCall(e.token(), e.call())));
    };
  }

  /**
   * A {@link TurnObserver} that maps every event through {@link #of(TurnEvent)} into {@code sink}.
   */
  public static TurnObserver observer(Consumer<Event> sink) {
    return event -> sink.accept(of(event));
  }

  /**
   * {@code {token, tool, args}} — the same shape {@link #of(TurnEvent)} emits for a live park, used
   * again by {@code ChatController#get} to redraw pending cards from a {@link ParkedCall} snapshot.
   * Args are pretty-printed via {@link com.fasterxml.jackson.databind.JsonNode#toPrettyString()},
   * which needs no application-supplied {@code ObjectMapper}.
   */
  static Map<String, Object> approvalCard(ParkedCall parked) {
    return Map.of(
        "token", parked.token().value(),
        "tool", parked.call().name(),
        "args", parked.call().arguments().toPrettyString());
  }

  private static boolean allowed(Decision decision) {
    return switch (decision) {
      case Decision.Allow ignored -> true;
      case Decision.Deny ignored -> false;
    };
  }
}
