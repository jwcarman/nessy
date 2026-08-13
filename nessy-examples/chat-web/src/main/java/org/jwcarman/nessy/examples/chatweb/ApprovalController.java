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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The resumed-segment stream: a human's verdict on a parked call, same event bridge as {@link
 * ChatController}.
 */
@RestController
@RequestMapping("/api/approvals")
public final class ApprovalController {

  private final Harness harness;
  private final ConversationStore store;
  private final ObjectMapper mapper;

  public ApprovalController(Harness harness, ConversationStore store, ObjectMapper mapper) {
    this.harness = harness;
    this.store = store;
    this.mapper = mapper;
  }

  @PostMapping("/{token}")
  public SseEmitter approve(@PathVariable String token, @RequestBody DecisionRequest body) {
    ParkToken parkToken = new ParkToken(token);
    // Peek-only (never consumes, unlike harness.resume): a card the store no longer parks is
    // rejected synchronously here, before the emitter is ever handed back, so the 409 below
    // reaches the caller as a normal HTTP response rather than an async stream failure.
    store
        .findPark(parkToken)
        .orElseThrow(() -> new IllegalArgumentException("unknown or settled park token: " + token));
    Decision decision = toDecision(body);
    SseEmitter emitter = new SseEmitter(0L);
    Thread.ofVirtual().start(() -> runResume(parkToken, decision, emitter));
    return emitter;
  }

  private void runResume(ParkToken token, Decision decision, SseEmitter emitter) {
    try {
      RunOutcome outcome =
          harness.resume(
              token,
              new ToolResolution.Decided(decision),
              SseEvents.observer(e -> ChatController.sendEvent(emitter, e)));
      ChatController.finish(emitter, outcome, mapper);
    } catch (RuntimeException e) {
      ChatController.fail(emitter, e);
    }
  }

  private static Decision toDecision(DecisionRequest body) {
    return switch (body.decision()) {
      case "allow" -> Decision.allow();
      case "deny" -> new Decision.Deny(Optional.ofNullable(body.reason()).orElse("denied"));
      default -> throw new IllegalArgumentException("unknown decision: " + body.decision());
    };
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleUnknownToken(IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
  }

  public record DecisionRequest(String decision, String reason) {}
}
