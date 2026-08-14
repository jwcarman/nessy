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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link TurnRunner} offline: no HTTP layer, no model call, just the virtual-thread handoff and the
 * exception-to-{@code done} translation. Neither test attaches the returned {@link SseEmitter} to a
 * real servlet response, so the smallest honest probe for "did the emitter finish" is the emitter's
 * own public completion guard: {@link SseEmitter#send} throws {@link IllegalStateException} once
 * {@code complete()}/{@code completeWithError()} has run (checked before any handler exists), so a
 * subsequent send failing that way is proof the background thread reached a terminal state — no
 * mocking library, no subclass, no reflection into Spring's package-private handler wiring.
 */
class TurnRunnerTest {

  private final TurnRunner runner = new TurnRunner();

  @Nested
  class A_turn_that_completes {

    @Test
    void hands_the_outcome_to_on_outcome_on_a_thread_other_than_the_caller_s()
        throws InterruptedException {
      RunOutcome outcome =
          new RunOutcome.Completed(
              ConversationState.newConversation(ConversationId.generate())
                  .with(ConversationStatus.COMPLETE));
      CountDownLatch handed = new CountDownLatch(1);
      AtomicReference<SseEmitter> emitterSeenByTurn = new AtomicReference<>();
      AtomicReference<SseEmitter> receivedEmitter = new AtomicReference<>();
      AtomicReference<RunOutcome> receivedOutcome = new AtomicReference<>();
      AtomicReference<Thread> receivedThread = new AtomicReference<>();
      Thread callingThread = Thread.currentThread();

      SseEmitter emitter =
          runner.run(
              e -> {
                emitterSeenByTurn.set(e);
                return outcome;
              },
              (e, o) -> {
                receivedEmitter.set(e);
                receivedOutcome.set(o);
                receivedThread.set(Thread.currentThread());
                handed.countDown();
              });

      assertThat(handed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(emitterSeenByTurn.get()).isSameAs(emitter);
      assertThat(receivedEmitter.get()).isSameAs(emitter);
      assertThat(receivedOutcome.get()).isEqualTo(outcome);
      assertThat(receivedThread.get()).isNotSameAs(callingThread);
      assertThat(receivedThread.get().isVirtual()).isTrue();
    }
  }

  @Nested
  class A_turn_that_throws {

    @Test
    void completes_the_emitter_with_error_instead_of_calling_on_outcome()
        throws InterruptedException {
      CountDownLatch onOutcomeCalled = new CountDownLatch(1);
      RuntimeException boom = new IllegalStateException("kaboom");

      SseEmitter emitter =
          runner.run(
              e -> {
                throw boom;
              },
              (e, o) -> onOutcomeCalled.countDown());

      awaitTerminal(emitter);
      assertThat(onOutcomeCalled.getCount()).isEqualTo(1);
    }

    /**
     * Polls the emitter's public {@link SseEmitter#send} guard until it reports the emitter already
     * completed, or fails the test after a generous timeout. The virtual thread's work here is pure
     * CPU (no I/O, no model call), so completion lands within milliseconds in practice.
     */
    private void awaitTerminal(SseEmitter emitter) {
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < deadline) {
        try {
          emitter.send("probe");
        } catch (IllegalStateException expectedOnceComplete) {
          assertThat(expectedOnceComplete).hasMessageContaining("kaboom");
          return;
        } catch (IOException e) {
          throw new AssertionError("unexpected IOException from an unattached emitter", e);
        }
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("interrupted while awaiting emitter completion", e);
        }
      }
      throw new AssertionError("emitter never completed within the timeout");
    }
  }
}
