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

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Turns nessy's {@link TurnEvent} narration into the starter's stable wire vocabulary (spec §4) —
 * named SSE payloads any browser or reference client can rely on across releases — plus a {@link
 * TurnObserver} that streams them to a sink and a {@link SseEmitter} send that tolerates a closed
 * client.
 *
 * <p>The switch in {@link #of(TurnEvent)} is deliberately exhaustive with no {@code default} arm,
 * against the sealed-grammar etiquette's own advice for extender code ({@link TurnEvent}'s javadoc
 * recommends one, for forward tolerance across majors). This module compiles in the same reactor as
 * {@code nessy-core} rather than consuming it as an external artifact across a major boundary, so
 * the etiquette's core rule — fail loud at compile time when the grammar grows — is the better
 * trade here: a new {@code TurnEvent} variant should not silently vanish from the wire.
 */
public final class TurnEventSse {

  private static final Logger LOGGER = LoggerFactory.getLogger(TurnEventSse.class);

  private TurnEventSse() {}

  /** One named SSE payload: {@code event.name()} on the wire, {@code payload} as its JSON data. */
  public record Event(String name, Map<String, Object> payload) {}

  /** Maps one {@link TurnEvent} to the named payload spec §4's endpoint table promises. */
  public static Event of(TurnEvent event) {
    return switch (event) {
      case TurnEvent.TextDelta(String text) -> new Event("delta", Map.of("text", text));
      case TurnEvent.ThinkingDelta(String text) -> new Event("thinking", Map.of("text", text));
      case TurnEvent.RedactedThinking( _) ->
          new Event("thinking", Map.of("text", "[redacted]"));
      case TurnEvent.ToolCallRequested(ToolCall call) ->
          new Event("tool-requested", Map.of("id", call.id(), "name", call.name()));
      case TurnEvent.ToolCallProgressed(ToolCall call, String message) ->
          new Event("tool-progress", Map.of("id", call.id(), "message", message));
      case TurnEvent.ToolCallDecided(ToolCall call, Decision decision) ->
          new Event("tool-decided", Map.of("id", call.id(), "allowed", allowed(decision)));
      case TurnEvent.ToolCallCompleted(ToolCall call, ToolResult result) ->
          new Event("tool-completed", Map.of("id", call.id(), "error", result.isError()));
      case TurnEvent.ToolCallParked(ToolCall call, ParkToken token) ->
          // A retried segment can narrate the same park twice, and a losing concurrent driver's
          // stream can see the park not at all (TurnEvent's own javadoc, at-least-once narration).
          // Neither gap is this module's to close: a reader who missed (or duplicated) this event
          // rebuilds the identical card from Agent.snapshot's parked-calls, the durable source of
          // record this live event is only a preview of.
          new Event(
              "tool-parked",
              Map.of(
                  "token", token.value(),
                  "tool", call.name(),
                  "args", call.arguments().toPrettyString()));
    };
  }

  /**
   * A {@link TurnObserver} that maps every event through {@link #of(TurnEvent)} into {@code sink}.
   */
  public static TurnObserver observer(Consumer<Event> sink) {
    return event -> sink.accept(of(event));
  }

  /**
   * Wraps {@code emitter.send} with the checked-IO try/catch: a closed tab is a broken pipe, not a
   * reason to fail the turn already driving in the background — narration never alters the record.
   */
  public static void send(SseEmitter emitter, Event event) {
    try {
      emitter.send(SseEmitter.event().name(event.name()).data(event.payload()));
    } catch (IOException e) {
      LOGGER.info("SSE send failed, likely a closed client; completing the stream", e);
      emitter.complete();
    }
  }

  private static boolean allowed(Decision decision) {
    return switch (decision) {
      case Decision.Allow _ -> true;
      case Decision.Deny _ -> false;
    };
  }
}
