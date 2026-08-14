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

import java.util.Map;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The world-volunteers-news door (spec §1's {@code Told} trigger): fire-and-forget. The incident id
 * mints the {@link ConversationId} directly (routing by external identity), so every signal about
 * the same incident joins that incident's story. The turn drives on a plain virtual thread, not the
 * starter's {@code TurnRunner}: that bean is SSE-shaped (returns an {@code SseEmitter}, built only
 * when {@code io.micrometer:context-propagation} is on the classpath) and this endpoint answers
 * {@code 202} immediately with no stream to write to — nothing here is a fit for it. This module
 * does not add {@code context-propagation} either, so tracing context is not propagated onto the
 * driving thread; the log (thread-per-incident lines, {@link IncidentLog}) is the observability
 * story here, exactly as it is in {@code night-watchman}.
 */
@RestController
public final class SignalController {

  private static final Logger LOGGER = LoggerFactory.getLogger(SignalController.class);

  private final Agent<String> agent;

  public SignalController(Agent<String> agent) {
    this.agent = agent;
  }

  @PostMapping("/signals")
  public ResponseEntity<Map<String, Object>> signal(@RequestBody SignalRequest body) {
    requireComplete(body);
    String incidentId = body.incidentId();
    ConversationId conversationId = new ConversationId("incident-" + incidentId);
    String line = "Signal for %s: %s — %s.".formatted(incidentId, body.kind(), body.detail());
    TurnObserver observer = IncidentLog.observer(incidentId, LOGGER);
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                agent.conversation(conversationId).tell(line, observer);
              } catch (RuntimeException e) {
                // The caller already has its 202 — this thread's only remaining audience is the
                // log (the same log-and-continue discipline as night-watchman's Watchman.round()).
                LOGGER.warn(
                    "[{}] signal drive failed: {} — the desk continues", incidentId, e.toString());
              }
            });
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("incident", incidentId));
  }

  private static void requireComplete(SignalRequest body) {
    if (body == null
        || isBlank(body.incidentId())
        || isBlank(body.kind())
        || isBlank(body.detail())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "incidentId, kind, and detail are all required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public record SignalRequest(String incidentId, String kind, String detail) {}
}
