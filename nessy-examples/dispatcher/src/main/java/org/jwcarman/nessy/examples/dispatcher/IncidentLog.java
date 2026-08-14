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
package org.jwcarman.nessy.examples.dispatcher;

import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;

/**
 * The narration every driven segment shares — signal-driven ({@link SignalController}) and
 * callback-driven ({@link CallbackController}) alike: text, tool requests, and parks (spec §3's
 * "the app logs the park token" bullet). Modeled on {@code night-watchman}'s {@code Watchman}
 * observer: a raw lambda pattern-matching over {@link TurnEvent}, sealed-grammar etiquette's {@code
 * default} arm included for forward tolerance.
 */
final class IncidentLog {

  private IncidentLog() {}

  /**
   * @param label the log-line tag: the incident id when driven from {@link SignalController}, the
   *     park token when driven from {@link CallbackController} (that door never has the incident id
   *     in hand — only the token the crew was given).
   */
  static TurnObserver observer(String label, Logger logger) {
    return event -> {
      switch (event) {
        case TurnEvent.TextDelta(String text) -> logger.info("[{}] says: {}", label, text);
        case TurnEvent.ToolCallRequested(ToolCall call) ->
            logger.info("[{}] tool: {}", label, call.name());
        case TurnEvent.ToolCallParked(ToolCall call, var token) ->
            logger.info("[{}] parked: tool={} token={}", label, call.name(), token.value());
        case TurnEvent.ToolCallCompleted(ToolCall call, var result) ->
            logger.info("[{}] tool completed: {} (error={})", label, call.name(), result.isError());
        // deliberate extender-tolerance default (chat-cli's discipline, cited by Watchman): the
        // log ignores variants it has no rendering for.
        default -> {}
      }
    };
  }
}
