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
package org.jwcarman.nessy.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class InMemoryDurableComputationBackendTest {

  private final InMemoryDurableComputationBackend backend = new InMemoryDurableComputationBackend();
  private static final ComputationId ID = ComputationId.of("tool:t:a:c1");
  private static final Continuation RESUME = new Continuation("RESUME_SCOPE", "{\"x\":1}");

  @Test
  void createIsGetOrCreate() {
    assertThat(backend.create(ID).created()).isTrue();
    assertThat(backend.create(ID).created()).isFalse();
    assertThat(backend.status(ID)).contains(ComputationStatus.PENDING);
  }

  @Test
  void awaitOnAPendingSlotRegistersDurably() {
    backend.create(ID);
    assertThat(backend.await(ID, RESUME)).isEqualTo(new AwaitResult.Registered());
    assertThat(backend.continuationsOf(ID)).containsExactly(RESUME);
  }

  @Test
  void reAwaitingTheSameContinuationIsOneRegistration() {
    backend.create(ID);
    backend.await(ID, RESUME);
    backend.await(ID, RESUME);
    assertThat(backend.continuationsOf(ID)).containsExactly(RESUME);
  }

  @Test
  void awaitAfterCompletionReturnsTheOutcomeAndRegistersNothing() {
    backend.create(ID);
    backend.complete(ID, new Outcome.Success("done"));
    assertThat(backend.await(ID, RESUME))
        .isEqualTo(new AwaitResult.AlreadyCompleted(new Outcome.Success("done")));
    assertThat(backend.continuationsOf(ID)).isEmpty();
  }

  @Test
  void completionFlipsExactlyOnce() {
    backend.create(ID);
    assertThat(backend.complete(ID, new Outcome.Success("first")))
        .isEqualTo(CompletionResult.COMPLETED);
    assertThat(backend.complete(ID, new Outcome.Failure("second")))
        .isEqualTo(CompletionResult.ALREADY_TERMINAL);
    assertThat(backend.status(ID)).contains(ComputationStatus.SUCCEEDED);
    assertThat(backend.await(ID, RESUME))
        .isEqualTo(new AwaitResult.AlreadyCompleted(new Outcome.Success("first")));
  }

  @Test
  void eachTerminalOutcomeMapsToItsStatus() {
    var failed = ComputationId.of("f");
    var cancelled = ComputationId.of("c");
    backend.create(failed);
    backend.create(cancelled);
    backend.complete(failed, new Outcome.Failure("boom"));
    backend.complete(cancelled, new Outcome.Cancelled("nobody cares"));
    assertThat(backend.status(failed)).contains(ComputationStatus.FAILED);
    assertThat(backend.status(cancelled)).contains(ComputationStatus.CANCELLED);
  }

  @Test
  void awaitOnAnUnknownIdFailsLoudlyButStatusIsJustEmpty() {
    var unknown = ComputationId.of("ghost");
    assertThatThrownBy(() -> backend.await(unknown, RESUME))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(backend.status(unknown)).isEmpty();
  }

  @Test
  void racingCompletersProduceExactlyOneFlip() throws Exception {
    backend.create(ID);
    int racers = 16;
    List<Callable<CompletionResult>> attempts = new ArrayList<>();
    for (int i = 0; i < racers; i++) {
      var outcome = new Outcome.Success("winner-" + i);
      attempts.add(() -> backend.complete(ID, outcome));
    }
    List<CompletionResult> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
      for (var future : pool.invokeAll(attempts)) {
        results.add(future.get());
      }
    }
    assertThat(results).isNotEmpty();
    assertThat(results.stream().filter(r -> r == CompletionResult.COMPLETED).count()).isEqualTo(1L);
  }

  @Test
  void completingAnUnknownIdBirthsTheSlotAlreadyTerminal() {
    var localBackend = new InMemoryDurableComputationBackend();
    var id = ComputationId.of("tool:t:a:c9");
    assertThat(localBackend.complete(id, new Outcome.Success("early")))
        .isEqualTo(CompletionResult.COMPLETED);
    assertThat(localBackend.status(id)).contains(ComputationStatus.SUCCEEDED);
  }

  @Test
  void createAfterAnEarlyCompletionFindsTheSlotAndAwaitAnswersAlreadyCompleted() {
    var localBackend = new InMemoryDurableComputationBackend();
    var id = ComputationId.of("tool:t:a:c9");
    localBackend.complete(id, new Outcome.Success("early"));
    assertThat(localBackend.create(id).created()).isFalse();
    assertThat(localBackend.await(id, new Continuation("T", "{}")))
        .isEqualTo(new AwaitResult.AlreadyCompleted(new Outcome.Success("early")));
  }

  @Test
  void anEarlyCompletionStillFlipsOnlyOnce() {
    var localBackend = new InMemoryDurableComputationBackend();
    var id = ComputationId.of("tool:t:a:c9");
    localBackend.complete(id, new Outcome.Success("early"));
    assertThat(localBackend.complete(id, new Outcome.Failure("late")))
        .isEqualTo(CompletionResult.ALREADY_TERMINAL);
  }

  @Test
  void awaitAndCompleteCannotMissEachOther() throws Exception {
    for (int round = 0; round < 100; round++) {
      var id = ComputationId.of("race-" + round);
      backend.create(id);
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        var awaited = pool.submit(() -> backend.await(id, RESUME));
        var completed = pool.submit(() -> backend.complete(id, new Outcome.Success("v")));
        AwaitResult result = awaited.get();
        completed.get();
        if (result instanceof AwaitResult.Registered) {
          assertThat(backend.continuationsOf(id)).containsExactly(RESUME);
        } else {
          assertThat(result).isEqualTo(new AwaitResult.AlreadyCompleted(new Outcome.Success("v")));
        }
      }
    }
  }
}
