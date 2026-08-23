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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.RaceOnceOnBatchSubstrate;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.WinnerBetweenChecksSubstrate;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class SubstrateComputationsTest {

  private static final ComputationId ID = ComputationId.of("tool:t:a:c1");
  private static final ToolInvocationId INVOCATION = new ToolInvocationId("response-1", "c1");
  private static final Continuation RETURN_ADDRESS = new Continuation("SCOPE_RESUME", "{\"x\":1}");

  private final Substrate store = new InMemorySubstrate();
  private final SubstrateComputations computations =
      new SubstrateComputations(store, TestMappers.plainlyPinned(), "computation", "outbox");

  private static long outboxCount(Substrate store) {
    return store.keys("outbox", 100).size();
  }

  private Outcome.Success success(Object domainPayload) {
    return new Outcome.Success(computations.encodeSuccess(domainPayload));
  }

  @Nested
  class CreatingAComputation {

    @Test
    void createIsGetOrCreate() {
      assertThat(computations.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty()).created())
          .isTrue();
      assertThat(computations.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty()).created())
          .isFalse();
    }

    @Test
    void aCreatedComputationIsFindable() {
      computations.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());

      Optional<PendingComputation> found = computations.find(ID);

      assertThat(found).isPresent();
      assertThat(found.get().id()).isEqualTo(ID);
      assertThat(found.get().invocation()).isEqualTo(INVOCATION);
      assertThat(found.get().returnAddress()).isEqualTo(RETURN_ADDRESS);
      assertThat(found.get().deadline()).isEmpty();
    }

    @Test
    void findOnAnUnknownIdIsEmpty() {
      assertThat(computations.find(ComputationId.of("ghost"))).isEmpty();
    }
  }

  @Nested
  class Completing {

    @Test
    void completingATransfersOwnershipToTheOutboxAndRemovesTheComputation() {
      computations.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());

      CompletionResult result = computations.complete(ID, success(ToolResult.ok("first")));

      assertThat(result).isEqualTo(CompletionResult.TRANSFERRED);
      assertThat(computations.find(ID)).isEmpty();
      assertThat(outboxCount(store)).isEqualTo(1L);
    }

    @Test
    void completingAnAbsentComputationIsBenign() {
      CompletionResult result = computations.complete(ID, success(ToolResult.ok("nobody home")));

      assertThat(result).isEqualTo(CompletionResult.ALREADY_DONE);
      assertThat(outboxCount(store)).isZero();
    }

    @Test
    void aSecondCompletionOnAnAlreadyTransferredComputationIsBenign() {
      computations.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());
      computations.complete(ID, success(ToolResult.ok("first")));

      CompletionResult second = computations.complete(ID, new Outcome.Failure("second"));

      assertThat(second).isEqualTo(CompletionResult.ALREADY_DONE);
      assertThat(outboxCount(store)).isEqualTo(1L); // still exactly the one delivery
    }

    @Test
    void aForeignSuccessPayloadIsRejectedAndNothingIsWritten() {
      computations.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());
      var foreign =
          new Outcome.Success(JsonNodeFactory.instance.objectNode().put("type", "mystery"));

      assertThatThrownBy(() -> computations.complete(ID, foreign))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(computations.find(ID)).isPresent();
      assertThat(outboxCount(store)).isZero();
    }

    @Test
    void racingCompletersProduceExactlyOneOwnershipTransfer() throws Exception {
      computations.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());
      int racers = 16;
      List<Callable<CompletionResult>> attempts = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        var outcome = success(ToolResult.ok("winner-" + i));
        attempts.add(() -> computations.complete(ID, outcome));
      }
      List<CompletionResult> results = new ArrayList<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
        for (var future : pool.invokeAll(attempts)) {
          results.add(future.get());
        }
      }
      assertThat(results).isNotEmpty();
      assertThat(results.stream().filter(r -> r == CompletionResult.TRANSFERRED).count())
          .isEqualTo(1L);
      assertThat(outboxCount(store)).isEqualTo(1L);
    }

    /**
     * The 2026-08-23 CI flake, pinned deterministically: the winner's transfer lands BETWEEN the
     * loser's first observation of the computation key and its next observation. Under the pre-fix
     * exists-then-pending order the loser saw the stale computation, then the winner's fresh
     * delivery, and took the converge branch for a second {@code TRANSFERRED}; pending-first makes
     * the loser re-observe and land on {@code ALREADY_DONE}. One transfer, one delivery, always.
     */
    @Test
    void aWinnerCommittingBetweenTheLosersPresenceChecksYieldsAlreadyDoneNotASecondTransfer() {
      var backing = new InMemorySubstrate();
      var winnerSide =
          new SubstrateComputations(backing, TestMappers.plainlyPinned(), "computation", "outbox");
      winnerSide.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());

      var winnerOutcome = success(ToolResult.ok("winner"));
      var raced =
          new WinnerBetweenChecksSubstrate(
              backing,
              "computation",
              ID.value(),
              () ->
                  assertThat(winnerSide.complete(ID, winnerOutcome))
                      .isEqualTo(CompletionResult.TRANSFERRED));
      var loser =
          new SubstrateComputations(raced, TestMappers.plainlyPinned(), "computation", "outbox");

      var loserOutcome = success(ToolResult.ok("loser"));
      assertThat(loser.complete(ID, loserOutcome)).isEqualTo(CompletionResult.ALREADY_DONE);
      assertThat(outboxCount(backing)).isEqualTo(1L);
      assertThat(backing.read("computation", ID.value())).isEmpty();
    }

    @Test
    void twoRacingCompletersProduceExactlyOneTransferredAndOneAlreadyDone() throws Exception {
      computations.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());
      var ready = new CountDownLatch(2);
      var go = new CountDownLatch(1);
      Callable<CompletionResult> first =
          () -> {
            ready.countDown();
            go.await();
            return computations.complete(ID, success(ToolResult.ok("a")));
          };
      Callable<CompletionResult> second =
          () -> {
            ready.countDown();
            go.await();
            return computations.complete(ID, success(ToolResult.ok("b")));
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
      assertThat(results).filteredOn(r -> r == CompletionResult.TRANSFERRED).hasSize(1);
      assertThat(results).filteredOn(r -> r == CompletionResult.ALREADY_DONE).hasSize(1);
    }
  }

  @Nested
  class InjectedConflictAtTheOwnershipTransfer {

    /**
     * The computation-to-delivery hand-off's own atomicity edge (spec §9): a deterministic
     * competitor write between {@code complete()}'s read and its own batch, via {@link
     * RaceOnceOnBatchSubstrate} — the same fixture {@code DeliveryWorkerTest} uses for the
     * delivery-to-fold hand-off. A {@code complete()} that let the resulting {@code
     * ConflictException} escape instead of re-reading and retrying would fail this test by
     * throwing; one that retried but re-read stale data would fail it by writing a second, orphaned
     * delivery.
     */
    @Test
    void aConflictingWriteBetweenReadAndBatchForcesCompleteToRetryAndLeavesExactlyOneDelivery() {
      var backing = new InMemorySubstrate();
      var setup =
          new SubstrateComputations(backing, TestMappers.plainlyPinned(), "computation", "outbox");
      setup.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());
      byte[] computationPayload = backing.read("computation", ID.value()).orElseThrow().payload();

      // the competitor re-saves the identical computation document, landing between complete()'s
      // read and its own batch — a genuine version bump complete() must retry past, not a
      // semantic change
      var raced =
          new RaceOnceOnBatchSubstrate(backing, "computation", ID.value(), computationPayload);
      var computations =
          new SubstrateComputations(raced, TestMappers.plainlyPinned(), "computation", "outbox");

      CompletionResult result = computations.complete(ID, success(ToolResult.ok("first")));

      assertThat(result).isEqualTo(CompletionResult.TRANSFERRED);
      assertThat(computations.find(ID)).isEmpty();
      assertThat(outboxCount(raced)).isEqualTo(1L);
    }
  }

  @Nested
  class Sharing {

    @Test
    void twoInstancesOverOneKernelShareTheComputation() {
      var writer =
          new SubstrateComputations(store, TestMappers.plainlyPinned(), "computation", "outbox");
      var reader =
          new SubstrateComputations(store, TestMappers.plainlyPinned(), "computation", "outbox");

      writer.create(ID, INVOCATION, RETURN_ADDRESS, Optional.empty());

      assertThat(reader.find(ID)).isPresent();

      writer.complete(ID, success(ToolResult.ok("shared")));

      assertThat(reader.find(ID)).isEmpty();
    }
  }
}
