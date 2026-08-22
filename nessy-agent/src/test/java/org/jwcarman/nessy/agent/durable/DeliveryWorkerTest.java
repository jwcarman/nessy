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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentResolver;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.agent.durable.OutcomeCodec.DeliveryDocument;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.RaceOnceOnBatchSubstrate;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The delivery worker's own atomicity edges (fix round 1, item 5) — the ownership-transfer and
 * commit-before-dispatch walks were verified clean at review; these three pin the retry, dedup, and
 * already-reconciled behaviors down as regression tests.
 */
class DeliveryWorkerTest {

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
            memory,
            stateStore,
            new NoopBacklog(),
            text -> List.of(new TextBlock(text)),
            new NoopModelCallExecutor(),
            new NoopToolCallExecutor(),
            AgentObserver.noop(),
            false,
            StalenessPolicy.never());
    return new DeliveryWorker<>(store, mapper, harness, resolver, Duration.ofHours(1));
  }

  private static void writeDelivery(
      Substrate store, ObjectMapper mapper, String key, Outcome outcome) {
    OutcomeCodec codec = new OutcomeCodec(mapper);
    Continuation destination =
        ScopeRouting.continuationFor(mapper, TYPE.name(), ID.value(), "response-1", CALL);
    byte[] payload =
        codec.toJson(new DeliveryDocument(destination, outcome)).getBytes(StandardCharsets.UTF_8);
    store.write("outbox", key, payload, 0);
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

      writeDelivery(raced, mapper, "d1", new Outcome.Success(ToolResult.ok("restarted")));
      var worker = workerOver(raced, mapper, (type, id) -> null);

      worker.nudge();

      List<Substrate.Entry> journal = raced.entries("memory", ID.value(), 1);
      assertThat(journal).hasSize(2); // exactly the assistant turn + the tool result, once
      assertThat(raced.keys("outbox", 10)).isEmpty();
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

      writeDelivery(store, mapper, "d1", new Outcome.Success(ToolResult.ok("first")));
      writeDelivery(store, mapper, "d2", new Outcome.Success(ToolResult.ok("second")));
      var worker = workerOver(store, mapper, (type, id) -> null);

      worker.nudge();

      assertThat(store.entries("memory", ID.value(), 1)).hasSize(2); // one fold, not two
      assertThat(store.keys("outbox", 10)).isEmpty(); // both deliveries consumed
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

      store.write("outbox", "bad", "not json at all".getBytes(StandardCharsets.UTF_8), 0);
      writeDelivery(store, mapper, "good", new Outcome.Success(ToolResult.ok("restarted")));
      var worker = workerOver(store, mapper, (type, id) -> null);

      worker.nudge();

      assertThat(store.entries("memory", ID.value(), 1)).hasSize(2); // the good delivery folded
      assertThat(store.keys("outbox", 10))
          .containsExactly("bad"); // left in place — never silently dropped
    }

    @Test
    void theHeartbeatSurvivesAnUndecodableDeliveryAndLaterDeliversAGoodOne() throws Exception {
      var mapper = TestMappers.plainlyPinned();
      var store = new InMemorySubstrate();
      var stateCodec = new StateCodec(mapper);
      byte[] statePayload = stateCodec.toJson(awaitingOneCall()).getBytes(StandardCharsets.UTF_8);
      store.write("state", ID.value(), statePayload, 0);
      store.write("outbox", "bad", "not json at all".getBytes(StandardCharsets.UTF_8), 0);

      var memory = new SubstrateMemory(store, ID.value(), mapper);
      var stateStore = new SubstrateAgentStateStore(store, ID.value(), Clock.systemUTC(), mapper);
      Harness<String> harness =
          TestAgents.harness(
              memory,
              stateStore,
              new NoopBacklog(),
              text -> List.of(new TextBlock(text)),
              new NoopModelCallExecutor(),
              new NoopToolCallExecutor(),
              AgentObserver.noop(),
              false,
              StalenessPolicy.never());
      var worker =
          new DeliveryWorker<String>(
              store, mapper, harness, (type, id) -> null, Duration.ofMillis(20));
      try {
        worker.start();
        Thread.sleep(100); // a few heartbeat ticks over the undecodable delivery, unharmed

        writeDelivery(store, mapper, "good", new Outcome.Success(ToolResult.ok("restarted")));

        List<Substrate.Entry> journal = List.of();
        long deadline = System.currentTimeMillis() + 3000;
        while (journal.isEmpty() && System.currentTimeMillis() < deadline) {
          Thread.sleep(20);
          journal = store.entries("memory", ID.value(), 1);
        }

        assertThat(journal).hasSize(2); // the heartbeat picked it up on its own, no nudge() called
        assertThat(store.keys("outbox", 10)).containsExactly("bad");
      } finally {
        worker.close();
      }
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

      writeDelivery(store, mapper, "stale", new Outcome.Success(ToolResult.ok("too late")));
      var worker = workerOver(store, mapper, (type, id) -> null);

      worker.nudge();

      assertThat(store.entries("memory", ID.value(), 1)).isEmpty(); // nothing to fold
      assertThat(store.keys("outbox", 10)).isEmpty(); // the stale delivery is still consumed
      assertThat(store.read("state", ID.value()).orElseThrow().version()).isEqualTo(versionBefore);
    }
  }
}
