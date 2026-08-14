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

import io.micrometer.context.ContextSnapshot;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The resumed-segment stream: a human's verdict on a parked call, same event bridge as {@link
 * ChatController}.
 */
@RestController
@RequestMapping("/api/approvals")
public final class ApprovalController {

  private final Harness harness;

  public ApprovalController(Harness harness) {
    this.harness = harness;
  }

  @PostMapping("/{token}")
  public SseEmitter approve(@PathVariable String token, @RequestBody DecisionRequest body) {
    ParkToken parkToken = new ParkToken(token);
    // Peek-only (never consumes, unlike harness.resume/approve/deny): a card the store no longer
    // parks is rejected synchronously here, before the emitter is ever handed back, so the 409
    // below reaches the caller as a normal HTTP response rather than an async stream failure.
    harness.peek(parkToken).orElseThrow(() -> new UnknownParkTokenException(parkToken));
    // Also validated synchronously, for the same reason: a malformed decision string is a genuine
    // caller error and should 400 as an ordinary HTTP response, not surface as an async stream
    // failure once runResume is already running on its own virtual thread.
    requireKnownDecision(body.decision());
    SseEmitter emitter = new SseEmitter(0L);
    // Same fix as ChatController#postMessage: a fresh virtual thread has an empty
    // current-Observation ThreadLocal, so restore this request thread's tracing context inside it
    // — otherwise the resumed segment's observations root a new trace instead of joining this
    // HTTP POST's.
    ContextSnapshot snapshot = ChatController.CONTEXT_SNAPSHOT_FACTORY.captureAll();
    Thread.ofVirtual().start(snapshot.wrap(() -> runResume(parkToken, body, emitter)));
    return emitter;
  }

  private void runResume(ParkToken token, DecisionRequest body, SseEmitter emitter) {
    try {
      TurnObserver observer = SseEvents.observer(e -> ChatController.sendEvent(emitter, e));
      RunOutcome outcome =
          "allow".equals(body.decision())
              ? harness.approve(token, observer)
              : harness.deny(token, Optional.ofNullable(body.reason()).orElse("denied"), observer);
      ChatController.finish(emitter, outcome);
    } catch (RuntimeException e) {
      ChatController.fail(emitter, e);
    }
  }

  private static void requireKnownDecision(String decision) {
    if (!"allow".equals(decision) && !"deny".equals(decision)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown decision: " + decision);
    }
  }

  @ExceptionHandler(UnknownParkTokenException.class)
  public ResponseEntity<Map<String, Object>> handleUnknownToken(UnknownParkTokenException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
  }

  public record DecisionRequest(String decision, String reason) {}
}
