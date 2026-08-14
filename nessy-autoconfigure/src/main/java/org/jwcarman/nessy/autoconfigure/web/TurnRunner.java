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

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.RunOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Drives one turn on a virtual thread and hands back the {@link SseEmitter} it streams onto — the
 * core of {@code ChatController.postMessage}'s pattern, generalized so any endpoint that owns a
 * {@code Supplier<RunOutcome>} and an outcome handler gets the same wiring for free.
 *
 * <p>{@link ContextSnapshotFactory#captureAll()} runs on the calling thread (the HTTP request
 * thread) before the virtual thread starts: a fresh virtual thread begins with every {@code
 * ThreadLocal} empty, so without this capture-and-restore, Micrometer's current-{@code Observation}
 * scope (and anything else registered with context-propagation) would parent the turn's own spans
 * onto nothing and start a new trace instead of continuing the request's.
 */
public final class TurnRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(TurnRunner.class);

  private final ContextSnapshotFactory contextSnapshotFactory =
      ContextSnapshotFactory.builder().build();

  /**
   * Creates a zero-timeout {@link SseEmitter}, captures this thread's context, and starts a virtual
   * thread that restores that context, runs {@code turn}, and hands its {@link RunOutcome} to
   * {@code onOutcome} alongside the emitter. {@code turn} is expected to stream its own narration
   * via a {@link TurnEventSse#observer(java.util.function.Consumer) TurnEventSse}-built observer as
   * it runs; a {@link RuntimeException} escaping {@code turn} instead ends the stream itself — one
   * {@code done} event naming the failure, then {@link SseEmitter#completeWithError}.
   */
  public SseEmitter run(Supplier<RunOutcome> turn, BiConsumer<SseEmitter, RunOutcome> onOutcome) {
    SseEmitter emitter = new SseEmitter(0L);
    ContextSnapshot snapshot = contextSnapshotFactory.captureAll();
    Thread.ofVirtual().start(snapshot.wrap(() -> runTurn(emitter, turn, onOutcome)));
    return emitter;
  }

  private void runTurn(
      SseEmitter emitter, Supplier<RunOutcome> turn, BiConsumer<SseEmitter, RunOutcome> onOutcome) {
    try {
      RunOutcome outcome = turn.get();
      onOutcome.accept(emitter, outcome);
    } catch (RuntimeException e) {
      LOGGER.warn("turn failed", e);
      String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
      TurnEventSse.send(
          emitter,
          new TurnEventSse.Event("done", Map.of("status", "ERROR", "failureReason", reason)));
      emitter.completeWithError(e);
    }
  }
}
