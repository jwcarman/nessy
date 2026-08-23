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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.computation.Continuation;
import org.jwcarman.nessy.api.computation.Outcome;
import org.jwcarman.nessy.api.computation.ToolInvocationId;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The type-filtered sweep (harness-first spec §5, new law): two harnesses of DIFFERENT {@link
 * AgentType}s sharing ONE substrate's outbox and computation keyspaces. Each worker's drain and
 * reap sweep must skip every record whose type is not its own harness's, before decoding further —
 * so a delivery or computation belonging to the OTHER type is left completely untouched (still
 * present, unmodified) by a sweep that isn't its own.
 */
class TypeFilteredSweepTest {

  /**
   * Fix round 1, item 5: reclaims every harness this test class built (directly or via {@link
   * org.jwcarman.nessy.agent.support.TestAgents} / {@code AgentFixture}) — each now owns a live
   * delivery-worker heartbeat (harness-first spec §4) that nothing else stops.
   */
  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  private static final AgentType ALPHA = AgentType.of("alpha");
  private static final AgentType BETA = AgentType.of("beta");
  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

  private static final class NoopModelCallExecutor implements ModelCallExecutor {
    @Override
    public void callModel(Sink sink) {
      // fixture only: these scenarios never need the model arm
    }
  }

  private static final class NoopToolCallExecutor implements ToolCallExecutor {
    @Override
    public void executeTool(ToolCall call, ModelResponseId responseId, Sink sink) {
      // fixture only: these scenarios resolve calls through the delivery pipeline, not this arm
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

  /** A {@code executeGrantedToolNow} double that counts its own invocations (spec §5's ask). */
  private static final class CountingGrantedToolExecutor implements ToolCallExecutor {
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public void executeTool(ToolCall call, ModelResponseId responseId, Sink sink) {
      // fixture only: the reaper's redispatch is the arm under test, not a fresh dispatch
    }

    @Override
    public ToolExecution executeGrantedToolNow(
        ToolCall call,
        CallAddress address,
        ToolInvocationId invocation,
        Optional<Substrate.Op> alsoCommit) {
      invocations.incrementAndGet();
      return new ToolExecution.Immediate(new ToolOutcome.Returned(ToolResult.ok("done")));
    }
  }

  private static Phase.AwaitingTools awaitingOneCall() {
    var assistantTurn = Message.assistant(List.of(new ToolUseBlock(CALL)));
    return new Phase.AwaitingTools(
        assistantTurn, Set.of(CALL.id()), List.of(), ModelResponseId.of("response-1"));
  }

  private static DeliveryWorker<String> workerOver(
      Substrate store, ObjectMapper mapper, AgentType type, AgentId id) {
    var memory = new SubstrateMemory(store, id.value(), mapper);
    var stateStore = new SubstrateAgentStateStore(store, id.value(), Clock.systemUTC(), mapper);
    Harness<String> harness =
        TestAgents.harness(
            type,
            memory,
            stateStore,
            new NoopBacklog(),
            text -> List.of(new TextBlock(text)),
            new NoopModelCallExecutor(),
            new NoopToolCallExecutor(),
            AgentObserver.noop(),
            false,
            StalenessPolicy.never());
    AgentResolver resolver = (t, i) -> null;
    return new DeliveryWorker<>(store, mapper, harness, resolver, Duration.ofHours(1));
  }

  private static void writeDelivery(
      Substrate store, ObjectMapper mapper, AgentType type, AgentId id, String key) {
    var codec = new OutcomeCodec(mapper);
    Continuation destination =
        ScopeRouting.continuationFor(mapper, type.name(), id.value(), "response-1", CALL);
    var outcome = new Outcome.Success(ToolResult.ok("restarted"));
    byte[] payload =
        codec
            .toJson(new OutcomeCodec.DeliveryDocument(destination, outcome))
            .getBytes(StandardCharsets.UTF_8);
    store.write("outbox", key, payload, 0);
  }

  @Nested
  class OutboxDrain {

    @Test
    void eachWorkerDeliversOnlyItsOwnTypesOutboxRecordsLeavingTheForeignOneUntouched() {
      var mapper = TestMappers.plainlyPinned();
      var substrate = new InMemorySubstrate();
      var stateCodec = new StateCodec(mapper);

      var alphaId = AgentId.of("scope-alpha");
      var betaId = AgentId.of("scope-beta");
      byte[] alphaState = stateCodec.toJson(awaitingOneCall()).getBytes(StandardCharsets.UTF_8);
      byte[] betaState = stateCodec.toJson(awaitingOneCall()).getBytes(StandardCharsets.UTF_8);
      substrate.write("state", alphaId.value(), alphaState, 0);
      substrate.write("state", betaId.value(), betaState, 0);

      writeDelivery(substrate, mapper, ALPHA, alphaId, "alpha-delivery");
      writeDelivery(substrate, mapper, BETA, betaId, "beta-delivery");

      var alphaWorker = workerOver(substrate, mapper, ALPHA, alphaId);
      var betaWorker = workerOver(substrate, mapper, BETA, betaId);

      // alpha's sweep delivers only its own record — beta's is still sitting there, untouched
      alphaWorker.nudge();
      assertThat(substrate.keys("outbox", 10)).containsExactly("beta-delivery");
      assertThat(substrate.entries("memory", alphaId.value(), 1)).isNotEmpty();
      assertThat(substrate.entries("memory", betaId.value(), 1)).isEmpty();

      // beta's own sweep now delivers the one record left — proving it was never touched, not
      // merely deferred
      betaWorker.nudge();
      assertThat(substrate.keys("outbox", 10)).isEmpty();
      assertThat(substrate.entries("memory", betaId.value(), 1)).isNotEmpty();
    }
  }

  @Nested
  class ReaperSweep {

    @Test
    void eachWorkerReapsOnlyItsOwnTypesComputationsLeavingTheForeignOneUntouched() {
      var mapper = TestMappers.plainlyPinned();
      var substrate = new InMemorySubstrate();
      var codec = new OutcomeCodec(mapper);

      var alphaId = AgentId.of("scope-alpha");
      var betaId = AgentId.of("scope-beta");
      var alphaKey = new CallAddress(ALPHA.name(), alphaId.value(), "response-1", "c1").execution();
      var betaKey = new CallAddress(BETA.name(), betaId.value(), "response-1", "c1").execution();

      writeOverdueRetryableComputation(substrate, mapper, codec, ALPHA, alphaId, alphaKey.value());
      writeOverdueRetryableComputation(substrate, mapper, codec, BETA, betaId, betaKey.value());

      var alphaExecutor = new CountingGrantedToolExecutor();
      var betaExecutor = new CountingGrantedToolExecutor();
      var alphaWorker = reaperWorkerOver(substrate, mapper, ALPHA, alphaId, alphaExecutor);
      var betaWorker = reaperWorkerOver(substrate, mapper, BETA, betaId, betaExecutor);

      // alpha's reap sweep touches only its own computation: its counting executor fires once,
      // beta's never fires, and beta's computation document is left exactly as it was
      Optional<Substrate.Document> betaBefore = substrate.read("computation", betaKey.value());
      alphaWorker.reapOnce();
      assertThat(alphaExecutor.invocations).hasValue(1);
      assertThat(betaExecutor.invocations).hasValue(0);
      assertThat(substrate.read("computation", betaKey.value())).isEqualTo(betaBefore);

      // beta's own sweep now reaps the one computation left untouched, proving it was never even
      // peeked at by alpha's sweep above
      betaWorker.reapOnce();
      assertThat(betaExecutor.invocations).hasValue(1);
      assertThat(alphaExecutor.invocations).hasValue(1); // unchanged by beta's own sweep
    }

    private static DeliveryWorker<String> reaperWorkerOver(
        Substrate store, ObjectMapper mapper, AgentType type, AgentId id, ToolCallExecutor tools) {
      var memory = new SubstrateMemory(store, id.value(), mapper);
      var stateStore = new SubstrateAgentStateStore(store, id.value(), Clock.systemUTC(), mapper);
      Harness<String> harness =
          TestAgents.harness(
              type,
              memory,
              stateStore,
              new NoopBacklog(),
              text -> List.of(new TextBlock(text)),
              new NoopModelCallExecutor(),
              tools,
              AgentObserver.noop(),
              false,
              StalenessPolicy.never());
      AgentResolver resolver = (t, i) -> null;
      return new DeliveryWorker<>(store, mapper, harness, resolver, Duration.ofHours(1));
    }

    private static void writeOverdueRetryableComputation(
        Substrate store,
        ObjectMapper mapper,
        OutcomeCodec codec,
        AgentType type,
        AgentId id,
        String key) {
      Continuation returnAddress =
          ScopeRouting.continuationFor(
              mapper,
              type.name(),
              id.value(),
              "response-1",
              CALL,
              RetrySemantics.RETRYABLE,
              Optional.of(Duration.ofMillis(1)));
      var invocation = new ToolInvocationId("response-1", CALL.id());
      // already overdue: the deadline is in the past the instant this is written
      var pending =
          new OutcomeCodec.PendingDocument(
              invocation, returnAddress, Optional.of(Instant.now().minusSeconds(1)));
      byte[] payload = codec.toJson(pending).getBytes(StandardCharsets.UTF_8);
      store.write("computation", key, payload, 0);
    }
  }

  @Nested
  class AgentTypeValidation {

    /**
     * F1 (blocking, correctness): the type threads straight into colon-delimited computation keys
     * ({@code tool:<agentType>:<agentId>:...}) — a colon in the type would make {@link
     * DeliveryWorker}'s reaper key-segment filter misparse the key and silently skip this harness's
     * own computations forever. Rejecting it at construction, with a message naming the offending
     * value, catches the mistake at the door instead of as a silent reaping failure downstream.
     */
    @Test
    void anAgentTypeNameContainingAColonIsRejectedWithATeachingMessage() {
      assertThatThrownBy(() -> AgentType.of("ops:eu"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ops:eu")
          .hasMessageContaining(":");
    }
  }
}
