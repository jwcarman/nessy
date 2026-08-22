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
package org.jwcarman.nessy.agent.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.durable.OutcomeCodec.SlotDocument;
import org.jwcarman.nessy.agent.support.RaceOnceOnWriteSubstrate;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.AwaitResult;
import org.jwcarman.nessy.durable.CompletionResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class StoredComputationsTest {

  private static final ComputationId ID = ComputationId.of("tool:t:a:c1");
  private static final Continuation RESUME = new Continuation("RESUME_SCOPE", "{\"x\":1}");

  private final Substrate store = new InMemorySubstrate();
  private final StoredComputations computations =
      new StoredComputations(store, TestMappers.plainlyPinned());
  private final OutcomeCodec codec = new OutcomeCodec(TestMappers.plainlyPinned());

  @Nested
  class CreatingASlot {

    @Test
    void createIsGetOrCreate() {
      assertThat(computations.create(ID).created()).isTrue();
      assertThat(computations.create(ID).created()).isFalse();
      assertThat(computations.status(ID)).contains(ComputationStatus.PENDING);
    }
  }

  @Nested
  class Awaiting {

    @Test
    void awaitOnAPendingSlotRegistersDurably() {
      computations.create(ID);
      assertThat(computations.await(ID, RESUME)).isEqualTo(new AwaitResult.Registered());
      assertThat(computations.continuationsOf(ID)).containsExactly(RESUME);
    }

    @Test
    void reAwaitingTheSameContinuationIsOneRegistration() {
      computations.create(ID);
      computations.await(ID, RESUME);
      computations.await(ID, RESUME);
      assertThat(computations.continuationsOf(ID)).containsExactly(RESUME);
    }

    @Test
    void duplicateContinuationRegistersOnceAcrossManyAwaits() {
      computations.create(ID);
      for (int i = 0; i < 5; i++) {
        assertThat(computations.await(ID, RESUME)).isEqualTo(new AwaitResult.Registered());
      }
      assertThat(computations.continuationsOf(ID)).containsExactly(RESUME);
    }

    @Test
    void awaitAfterCompletionReturnsTheOutcomeAndRegistersNothing() {
      computations.create(ID);
      computations.complete(ID, new Outcome.Success(ToolResult.ok("done")));
      assertThat(computations.await(ID, RESUME))
          .isEqualTo(new AwaitResult.AlreadyCompleted(new Outcome.Success(ToolResult.ok("done"))));
      assertThat(computations.continuationsOf(ID)).isEmpty();
    }

    @Test
    void awaitOnAnUnknownIdFailsLoudlyButStatusIsJustEmpty() {
      var unknown = ComputationId.of("ghost");
      assertThatThrownBy(() -> computations.await(unknown, RESUME))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(computations.status(unknown)).isEmpty();
    }

    @Test
    void continuationsOfAnUnknownIdFailsLoudly() {
      var unknown = ComputationId.of("ghost");
      assertThatThrownBy(() -> computations.continuationsOf(unknown))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Completing {

    @Test
    void completionFlipsExactlyOnce() {
      computations.create(ID);
      assertThat(computations.complete(ID, new Outcome.Success(ToolResult.ok("first"))))
          .isEqualTo(CompletionResult.COMPLETED);
      assertThat(computations.complete(ID, new Outcome.Failure("second")))
          .isEqualTo(CompletionResult.ALREADY_TERMINAL);
      assertThat(computations.status(ID)).contains(ComputationStatus.SUCCEEDED);
      assertThat(computations.await(ID, RESUME))
          .isEqualTo(new AwaitResult.AlreadyCompleted(new Outcome.Success(ToolResult.ok("first"))));
    }

    @Test
    void eachTerminalOutcomeMapsToItsStatus() {
      var failed = ComputationId.of("f");
      var cancelled = ComputationId.of("c");
      computations.create(failed);
      computations.create(cancelled);
      computations.complete(failed, new Outcome.Failure("boom"));
      computations.complete(cancelled, new Outcome.Cancelled("nobody cares"));
      assertThat(computations.status(failed)).contains(ComputationStatus.FAILED);
      assertThat(computations.status(cancelled)).contains(ComputationStatus.CANCELLED);
    }

    @Test
    void completingAnUnknownIdBirthsTheSlotAlreadyTerminal() {
      var id = ComputationId.of("tool:t:a:c9");
      assertThat(computations.complete(id, new Outcome.Success(ToolResult.ok("early"))))
          .isEqualTo(CompletionResult.COMPLETED);
      assertThat(computations.status(id)).contains(ComputationStatus.SUCCEEDED);
    }

    @Test
    void createAfterAnEarlyCompletionFindsTheSlotAndAwaitAnswersAlreadyCompleted() {
      var id = ComputationId.of("tool:t:a:c9");
      computations.complete(id, new Outcome.Success(ToolResult.ok("early")));
      assertThat(computations.create(id).created()).isFalse();
      assertThat(computations.await(id, new Continuation("T", "{}")))
          .isEqualTo(new AwaitResult.AlreadyCompleted(new Outcome.Success(ToolResult.ok("early"))));
    }

    @Test
    void anEarlyCompletionStillFlipsOnlyOnce() {
      var id = ComputationId.of("tool:t:a:c9");
      computations.complete(id, new Outcome.Success(ToolResult.ok("early")));
      assertThat(computations.complete(id, new Outcome.Failure("late")))
          .isEqualTo(CompletionResult.ALREADY_TERMINAL);
    }

    @Test
    void racingCompletersProduceExactlyOneFlip() throws Exception {
      computations.create(ID);
      int racers = 16;
      List<Callable<CompletionResult>> attempts = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        var outcome = new Outcome.Success(ToolResult.ok("winner-" + i));
        attempts.add(() -> computations.complete(ID, outcome));
      }
      List<CompletionResult> results = new ArrayList<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
        for (var future : pool.invokeAll(attempts)) {
          results.add(future.get());
        }
      }
      assertThat(results).isNotEmpty();
      assertThat(results.stream().filter(r -> r == CompletionResult.COMPLETED).count())
          .isEqualTo(1L);
    }

    @Test
    void twoRacingCompletesProduceExactlyOneCompletedAndOneAlreadyTerminal() throws Exception {
      computations.create(ID);
      var ready = new CountDownLatch(2);
      var go = new CountDownLatch(1);
      Callable<CompletionResult> first =
          () -> {
            ready.countDown();
            go.await();
            return computations.complete(ID, new Outcome.Success(ToolResult.ok("a")));
          };
      Callable<CompletionResult> second =
          () -> {
            ready.countDown();
            go.await();
            return computations.complete(ID, new Outcome.Success(ToolResult.ok("b")));
          };
      List<CompletionResult> results;
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        var futureA = pool.submit(first);
        var futureB = pool.submit(second);
        ready.await();
        go.countDown();
        results = List.of(futureA.get(), futureB.get());
      }
      assertThat(results).hasSize(2);
      assertThat(results).filteredOn(r -> r == CompletionResult.COMPLETED).hasSize(1);
      assertThat(results).filteredOn(r -> r == CompletionResult.ALREADY_TERMINAL).hasSize(1);
    }

    @Test
    void aForeignSuccessPayloadIsRejectedAndTheDocumentStaysAbsent() {
      var id = ComputationId.of("tool:t:a:c-foreign");
      var foreign = new Outcome.Success("a bare string");

      assertThatThrownBy(() -> computations.complete(id, foreign))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(store.read("computation", id.value())).isEmpty();
    }
  }

  @Nested
  class Sharing {

    @Test
    void twoInstancesOverOneKernelShareTheComputation() {
      var writer = new StoredComputations(store, TestMappers.plainlyPinned());
      var reader = new StoredComputations(store, TestMappers.plainlyPinned());

      writer.create(ID);
      writer.await(ID, RESUME);
      writer.complete(ID, new Outcome.Success(ToolResult.ok("shared")));

      assertThat(reader.status(ID)).contains(ComputationStatus.SUCCEEDED);
      assertThat(reader.continuationsOf(ID)).containsExactly(RESUME);
      assertThat(reader.await(ID, RESUME))
          .isEqualTo(
              new AwaitResult.AlreadyCompleted(new Outcome.Success(ToolResult.ok("shared"))));
    }
  }

  @Nested
  class RaceFreeAwait {

    @Test
    void awaitAndCompleteCannotMissEachOther() throws Exception {
      for (int round = 0; round < 100; round++) {
        var id = ComputationId.of("race-" + round);
        computations.create(id);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
          var awaited = pool.submit(() -> computations.await(id, RESUME));
          var completed =
              pool.submit(() -> computations.complete(id, new Outcome.Success(ToolResult.ok("v"))));
          AwaitResult result = awaited.get();
          completed.get();
          if (result instanceof AwaitResult.Registered) {
            assertThat(computations.continuationsOf(id)).containsExactly(RESUME);
          } else {
            assertThat(result)
                .isEqualTo(
                    new AwaitResult.AlreadyCompleted(new Outcome.Success(ToolResult.ok("v"))));
          }
        }
      }
    }
  }

  @Nested
  class DeterministicConflictRetries {

    @Test
    void completesFlipConflictRetriesToAlreadyTerminalWithTheCompetitorsOutcome() {
      computations.create(ID);
      var competitorOutcome = new Outcome.Failure("competitor");
      byte[] competitorPayload =
          codec
              .toJson(new SlotDocument(ComputationStatus.FAILED, competitorOutcome, List.of()))
              .getBytes(StandardCharsets.UTF_8);
      var raced =
          new StoredComputations(
              new RaceOnceOnWriteSubstrate(store, competitorPayload), TestMappers.plainlyPinned());

      CompletionResult result = raced.complete(ID, new Outcome.Success(ToolResult.ok("mine")));

      assertThat(result).isEqualTo(CompletionResult.ALREADY_TERMINAL);
      assertThat(computations.await(ID, RESUME))
          .isEqualTo(new AwaitResult.AlreadyCompleted(competitorOutcome));
    }

    @Test
    void completesRulingSixAbsentConflictRetriesAndStillCompletes() {
      var id = ComputationId.of("tool:t:a:ruling-six-race");
      byte[] competitorPayload =
          codec
              .toJson(new SlotDocument(ComputationStatus.PENDING, null, List.of()))
              .getBytes(StandardCharsets.UTF_8);
      var raced =
          new StoredComputations(
              new RaceOnceOnWriteSubstrate(store, competitorPayload), TestMappers.plainlyPinned());

      CompletionResult result = raced.complete(id, new Outcome.Success(ToolResult.ok("mine")));

      assertThat(result).isEqualTo(CompletionResult.COMPLETED);
      assertThat(computations.status(id)).contains(ComputationStatus.SUCCEEDED);
    }

    @Test
    void awaitConflictRetriesAndKeepsBothContinuations() {
      computations.create(ID);
      var other = new Continuation("OTHER", "{}");
      byte[] competitorPayload =
          codec
              .toJson(new SlotDocument(ComputationStatus.PENDING, null, List.of(other)))
              .getBytes(StandardCharsets.UTF_8);
      var raced =
          new StoredComputations(
              new RaceOnceOnWriteSubstrate(store, competitorPayload), TestMappers.plainlyPinned());

      AwaitResult result = raced.await(ID, RESUME);

      assertThat(result).isEqualTo(new AwaitResult.Registered());
      assertThat(computations.continuationsOf(ID)).containsExactlyInAnyOrder(other, RESUME);
    }
  }
}
