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
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The world-answers-a-question door (spec §1's {@code Resolved} trigger): the crew's two signals,
 * both driven synchronously — unlike {@link SignalController}'s fire-and-forget 202, the caller
 * here deserves the settled answer in the response itself.
 */
@RestController
public final class CallbackController {

  private static final Logger LOGGER = LoggerFactory.getLogger(CallbackController.class);

  private final Agent<String> agent;

  public CallbackController(Agent<String> agent) {
    this.agent = agent;
  }

  /**
   * Resolves the park with the crew's outcome. {@code agent.resume} keeps the registry entry alive
   * after resolution rather than consuming it (design of record) — a redelivered callback against
   * the same token re-drives the same resolution, but the fold's own still-outstanding check drains
   * it quietly instead of replaying the tool call, so a duplicate call answers {@code 200} with the
   * same settled status rather than erroring or re-running anything (spec §3's
   * javadoc-the-idempotency instruction).
   */
  @PostMapping("/callbacks/{token}")
  public ResponseEntity<Map<String, Object>> complete(
      @PathVariable String token, @RequestBody OutcomeRequest body) {
    requireOutcome(body);
    ParkToken parkToken = new ParkToken(token);
    TurnObserver observer = IncidentLog.observer(token, LOGGER);
    RunOutcome outcome =
        agent.resume(
            parkToken, new ToolResolution.Completed(ToolResult.ok(body.outcome())), observer);
    return ResponseEntity.ok(Map.of("status", outcome.state().status().name()));
  }

  /**
   * Narrates progress from the crew still out in the world. An unknown token, or one the
   * conversation no longer lists as outstanding (already settled), has nowhere left to land —
   * {@link Agent#progress} drops it and answers {@code false}; that is legal, not an error, so this
   * endpoint always answers {@code 200} (spec §3).
   */
  @PostMapping("/callbacks/{token}/progress")
  public ResponseEntity<Map<String, Object>> progress(
      @PathVariable String token, @RequestBody ProgressRequest body) {
    requireMessage(body);
    boolean heard = agent.progress(new ParkToken(token), body.message());
    return ResponseEntity.ok(Map.of("heard", heard));
  }

  @ExceptionHandler(UnknownParkTokenException.class)
  public ResponseEntity<Map<String, Object>> handleUnknownToken(UnknownParkTokenException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
  }

  private static void requireOutcome(OutcomeRequest body) {
    if (body == null || isBlank(body.outcome())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outcome is required");
    }
  }

  private static void requireMessage(ProgressRequest body) {
    if (body == null || isBlank(body.message())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public record OutcomeRequest(String outcome) {}

  public record ProgressRequest(String message) {}
}
