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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RaceOnceOnBatchSubstrate;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.ThrowingThenDelegatingMemory;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The delivery worker's own atomicity edges (fix round 1, item 5) — the ownership-transfer and
 * commit-before-dispatch walks were verified clean at review; these three pin the retry, dedup, and
 * already-reconciled behaviors down as regression tests.
 */
class DeliveryWorkerTest {

  /**
   * Fix round 1, item 5: reclaims every harness this test class built (directly or via {@link
   * org.jwcarman.nessy.agent.support.TestAgents} / {@code AgentFixture}) — each now owns a live
   * delivery-worker heartbeat (harness-first spec §4) that nothing else stops.
   */
  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  private static final AgentType TYPE = AgentType.of("t");
  private static final AgentId ID = AgentId.of("demo");
  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

  private static final class NoopModelCallExecutor implements ModelCallExecutor {
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public void callModel(Sink sink) {
      invocations
          .incrementAndGet(); // never calls the sink — the fold's own persistence is what's under
      // test
    }
  }

  private static final class NoopToolCallExecutor implements ToolCallExecutor {
    @Override
    public void executeTool(ToolCall call, ModelResponseId responseId, Sink sink) {
      // unused by these scenarios: every delivery here resolves the scope's only pending call
    }
  }

  private static final class NoopBacklog implements Backlog<String> {
    @Override
    public void add(String observation) {
      // fixture only
    }

    @Override
    public Optional<String> poll() {
      return Optional.empty();
    }
  }

  private static Phase.AwaitingTools awaitingOneCall() {
    Message assistantTurn = Message.assistant(List.of(new ToolUseBlock(CALL)));
    return new Phase.AwaitingTools(
        assistantTurn, Set.of(CALL.id()), List.of(), ModelResponseId.of("response-1"));
  }

  private static DeliveryWorker<String> workerOver(
      Substrate store, ObjectMapper mapper, AgentResolver resolver) {
    var memory = new SubstrateMemory(store, ID.value(), mapper);
    var stateStore = new SubstrateAgentStateStore(store, ID.value(), Clock.systemUTC(), mapper);
    Harness<String> harness =
        TestAgents.harness(
            TYPE,
            memory,
            stateStore,
            new NoopBacklog(),
            text -> List.of(new TextBlock(text)),
            new NoopModelCallExecutor(),
            new NoopToolCallExecutor(),
            AgentObserver.noop(),
            false,
            StalenessPolicy.never());
    return new DeliveryWorker<>(store, mapper, harness, resolver);
  }

  private static void writeDelivery(
      Substrate store, ObjectMapper mapper, String key, Outcome outcome) {
    OutcomeCodec codec = new OutcomeCodec(mapper);
    Continuation destination =
        ScopeRouting.continuationFor(mapper, TYPE.name(), ID.value(), "response-1", CALL);
    byte[] payload =
        codec
            .toJson(new OutcomeCodec.DeliveryDocument(destination, outcome))
            .getBytes(StandardCharsets.UTF_8);
    store.write(Kinds.outbox(TYPE), key, payload, 0);
  }

  @Nested
  class ConcurrentStateWriteBetweenReadAndBatch {

    @Test
    void aConflictingStateWriteForcesARetryAndLeavesNoOrphanJournalEntries() {
      var mapper = TestMappers.plainlyPinned();
      var backing = new InMemorySubstrate();
      var stateCodec = new StateCodec(mapper);
      byte[] statePayload = stateCodec.toJson(awaitingOneCall()).getBytes(StandardCharsets.UTF_8);
      backing.write("state", ID.value(), statePayload, 0);

      // the competitor re-saves the identical phase, landing between the worker's read and its
      // own batch — a genuine version bump the worker must retry past, not a semantic change
      var raced = new RaceOnceOnBatchSubstrate(backing, "state", ID.value(), statePayload);

      writeDelivery(
          raced,
          mapper,
          "d1",
          new Outcome.Success(new OutcomeCodec(mapper).encodeSuccess(ToolResult.ok("restarted"))));
      var worker = workerOver(raced, mapper, (type, id) -> null);

      worker.nudge();

      List<Substrate.Entry> journal = raced.entries("memory", ID.value(), 1);
      assertThat(journal).hasSize(2); // exactly the assistant turn + the tool result, once
      assertThat(raced.keys(Kinds.outbox(TYPE), 10)).isEmpty();
      assertThat(raced.read("state", ID.value())).isPresent();
      Phase folded =
          stateCodec.phase(
              new String(
                  raced.read("state", ID.value()).orElseThrow().payload(), StandardCharsets.UTF_8));
      assertThat(folded).isInstanceOf(Phase.AwaitingModel.class);
    }
  }

  @Nested
  class DuplicateDeliveryForTheSameCall {

    @Test
    void twoDeliveriesForOneCallFoldOnceAndBothAreConsumed() {
      var mapper = TestMappers.plainlyPinned();
      var store = new InMemorySubstrate();
      var stateCodec = new StateCodec(mapper);
      byte[] statePayload = stateCodec.toJson(awaitingOneCall()).getBytes(StandardCharsets.UTF_8);
      store.write("state", ID.value(), statePayload, 0);

      writeDelivery(
          store,
          mapper,
          "d1",
          new Outcome.Success(new OutcomeCodec(mapper).encodeSuccess(ToolResult.ok("first"))));
      writeDelivery(
          store,
          mapper,
          "d2",
          new Outcome.Success(new OutcomeCodec(mapper).encodeSuccess(ToolResult.ok("second"))));
      var worker = workerOver(store, mapper, (type, id) -> null);

      worker.nudge();

      assertThat(store.entries("memory", ID.value(), 1)).hasSize(2); // one fold, not two
      assertThat(store.keys(Kinds.outbox(TYPE), 10)).isEmpty(); // both deliveries consumed
      Phase folded =
          stateCodec.phase(
              new String(
                  store.read("state", ID.value()).orElseThrow().payload(), StandardCharsets.UTF_8));
      assertThat(folded).isInstanceOf(Phase.AwaitingModel.class);
    }
  }

  @Nested
  class UndecodableDeliveries {

    @Test
    void anUndecodableDeliveryDoesNotBlockTheNextOneFromFolding() {
      var mapper = TestMappers.plainlyPinned();
      var store = new InMemorySubstrate();
      var stateCodec = new StateCodec(mapper);
      byte[] statePayload = stateCodec.toJson(awaitingOneCall()).getBytes(StandardCharsets.UTF_8);
      store.write("state", ID.value(), statePayload, 0);

      store.write(Kinds.outbox(TYPE), "bad", "not json at all".getBytes(StandardCharsets.UTF_8), 0);
      writeDelivery(
          store,
          mapper,
          "good",
          new Outcome.Success(new OutcomeCodec(mapper).encodeSuccess(ToolResult.ok("restarted"))));
      var worker = workerOver(store, mapper, (type, id) -> null);

      worker.nudge();

      assertThat(store.entries("memory", ID.value(), 1)).hasSize(2); // the good delivery folded
      assertThat(store.keys(Kinds.outbox(TYPE), 10))
          .containsExactly("bad"); // left in place — never silently dropped
    }
  }

  @Nested
  class AlreadyReconciledDelivery {

    @Test
    void aDeliveryForAnAlreadyReconciledCallLeavesTheStateVersionUnchangedAndRemovesTheDelivery() {
      var mapper = TestMappers.plainlyPinned();
      var store = new InMemorySubstrate();
      var stateCodec = new StateCodec(mapper);
      // the scope has already moved on: AwaitingModel, nothing pending
      byte[] statePayload =
          stateCodec.toJson(new Phase.AwaitingModel()).getBytes(StandardCharsets.UTF_8);
      store.write("state", ID.value(), statePayload, 0);
      long versionBefore = store.read("state", ID.value()).orElseThrow().version();

      writeDelivery(
          store,
          mapper,
          "stale",
          new Outcome.Success(new OutcomeCodec(mapper).encodeSuccess(ToolResult.ok("too late"))));
      var worker = workerOver(store, mapper, (type, id) -> null);

      worker.nudge();

      assertThat(store.entries("memory", ID.value(), 1)).isEmpty(); // nothing to fold
      assertThat(store.keys(Kinds.outbox(TYPE), 10))
          .isEmpty(); // the stale delivery is still consumed
      assertThat(store.read("state", ID.value()).orElseThrow().version()).isEqualTo(versionBefore);
    }
  }

  record NoInput() {}

  private static final class CountingTool implements Tool<NoInput> {
    final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String name() {
      return "restart";
    }

    @Override
    public String description() {
      return "counts every invocation";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      invocations.incrementAndGet();
      return Awaited.ready(ToolResult.ok("restarted"));
    }
  }

  /**
   * Remembrance spec §1: memory left the atomic batch, so the worker no longer inspects what kind
   * of {@code Memory} a scope is wired with — the retired {@code requirePlainSubstrateMemory} guard
   * this class used to run before every granted tool. A genuinely non-substrate {@code Memory}
   * (here, {@link VerbatimMemory}, which keeps its own in-process list and never touches the
   * worker's substrate at all) is now a first-class citizen: the tool runs, its outcome folds, and
   * the delivery is consumed exactly as it would be over a {@link SubstrateMemory}.
   */
  @Nested
  class AnyMemoryIsFirstClass {

    @Test
    void aNonSubstrateMemoryScopeRunsTheToolAndConsumesTheDelivery() {
      var mapper = TestMappers.plainlyPinned();
      var store = new InMemorySubstrate();
      var tool = new CountingTool();
      var registry = ToolRegistry.of(ToolGrant.grant(tool, UsagePolicy.allow()));
      var pump = new PumpedExecutor();
      var executor =
          new RegistryToolCallExecutor(
              registry, TYPE, ID, new RecordingTurnObserver(), pump, mapper);
      var memory = new VerbatimMemory();
      Harness<String> harness =
          TestAgents.harness(
              TYPE,
              memory,
              new SubstrateAgentStateStore(store, ID.value(), Clock.systemUTC(), mapper),
              new NoopBacklog(),
              text -> List.of(new TextBlock(text)),
              new NoopModelCallExecutor(),
              executor,
              AgentObserver.noop(),
              false,
              StalenessPolicy.never());
      var worker = new DeliveryWorker<String>(store, mapper, harness, (t, i) -> null);

      writeDelivery(
          store,
          mapper,
          "grant",
          new Outcome.Success(new OutcomeCodec(mapper).encodeSuccess(Decision.allow())));

      worker.nudge();
      pump.pumpUntilQuiet();

      assertThat(tool.invocations).hasValue(1); // the tool ran
      assertThat(store.keys(Kinds.outbox(TYPE), 10)).isEmpty(); // the delivery is consumed
    }
  }

  /**
   * Remembrance spec §1 law 1 (fix round 1 Q1): a throwing {@code remember} must abort the fold
   * attempt BEFORE the commit batch ever runs — the delivery stays exactly as it was, undeleted,
   * and the scope's state is untouched, not partially advanced. Once memory heals, the very next
   * redrive folds cleanly: the delivery is consumed, the phase advances, and — because the first
   * attempt's {@code remember} never got far enough to record anything — there is exactly one
   * {@link Remembrance.ToolExchange} for the call, not two.
   */
  @Nested
  class AThrowingMemoryLeavesTheDeliveryPendingThenHealsOnRedrive {

    @Test
    void aThrowingRememberAbortsBeforeTheCommitBatchThenHealsOnTheNextNudge() {
      var mapper = TestMappers.plainlyPinned();
      var store = new InMemorySubstrate();
      var stateCodec = new StateCodec(mapper);
      byte[] statePayload = stateCodec.toJson(awaitingOneCall()).getBytes(StandardCharsets.UTF_8);
      store.write("state", ID.value(), statePayload, 0);
      long versionBefore = store.read("state", ID.value()).orElseThrow().version();

      writeDelivery(
          store,
          mapper,
          "d1",
          new Outcome.Success(new OutcomeCodec(mapper).encodeSuccess(ToolResult.ok("restarted"))));

      var recording = new RecordingMemory();
      var memory = new ThrowingThenDelegatingMemory(recording, 1); // throws once, then heals
      Harness<String> harness =
          TestAgents.harness(
              TYPE,
              memory,
              new SubstrateAgentStateStore(store, ID.value(), Clock.systemUTC(), mapper),
              new NoopBacklog(),
              text -> List.of(new TextBlock(text)),
              new NoopModelCallExecutor(),
              new NoopToolCallExecutor(),
              AgentObserver.noop(),
              false,
              StalenessPolicy.never());
      var worker = new DeliveryWorker<String>(store, mapper, harness, (t, i) -> null);

      worker.nudge(); // the throwing arm — must not throw out of nudge() itself

      assertThat(store.keys(Kinds.outbox(TYPE), 10)).containsExactly("d1"); // survives, undeleted
      assertThat(store.read("state", ID.value()).orElseThrow().version()).isEqualTo(versionBefore);

      worker.nudge(); // memory has healed — the redrive folds cleanly

      assertThat(store.keys(Kinds.outbox(TYPE), 10)).isEmpty(); // consumed
      Phase folded =
          stateCodec.phase(
              new String(
                  store.read("state", ID.value()).orElseThrow().payload(), StandardCharsets.UTF_8));
      assertThat(folded).isInstanceOf(Phase.AwaitingModel.class); // phase advanced

      List<Remembrance.ToolExchange> exchangesForTheCall =
          recording.facts().stream()
              .filter(Remembrance.ToolExchange.class::isInstance)
              .map(Remembrance.ToolExchange.class::cast)
              .filter(exchange -> exchange.call().id().equals(CALL.id()))
              .toList();
      assertThat(exchangesForTheCall).hasSize(1); // exactly one, despite the failed first attempt
    }
  }
}
