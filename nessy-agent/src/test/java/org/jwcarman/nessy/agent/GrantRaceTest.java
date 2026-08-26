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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.store.SubstrateAgentPhaseStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The double-drain race (durable-deliveries spec §5a invariant 5, fix round 2 item (c)): two
 * concurrent drains of the SAME grant — two threads both calling {@link DeliveryWorker#nudge()} at
 * once, each submitting a drain pass to the shared {@link ComputationScheduler} — must execute the
 * granted tool exactly once. Before Continuum adoption, both racers would read the delivery
 * present, both call the tool, and both attempt to fold — a duplicate external side effect with no
 * crash involved.
 *
 * <p>Continuum's own lease is what makes this test pass now (continuum-adoption spec §3, §7): a
 * {@code deliverResults} pass claims a delivery under an exclusive lease before handing it to this
 * worker's consumer, so only one of the two racing drain passes ever sees the grant at all — the
 * other's pass finds nothing claimable and returns having done nothing. There is no in-process
 * {@code claiming} set doing this any more; the single-winner guarantee is Continuum's, not this
 * module's.
 */
class GrantRaceTest {

  /** Any term: nothing in this test clips it. */
  private static final Duration TERM = Duration.ofDays(7);

  /** The harness ceilings, as HarnessConfig sets them (deferral-by-callback spec §5). */
  private static final Duration APPROVAL_CEILING = Duration.ofDays(7);

  private static final Duration TOOL_CEILING = Duration.ofDays(1);

  /**
   * Fix round 1, item 5: reclaims every harness this test class built (directly or via {@link
   * org.jwcarman.nessy.agent.support.TestAgents} / {@code AgentFixture}) — each now owns a live
   * delivery-worker heartbeat (harness-first spec §4) that nothing else stops.
   */
  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
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
      return "gated behind approval; counts every invocation";
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

  private static final class QueueBacklog implements Backlog<String> {
    private final java.util.Deque<String> queue = new java.util.ArrayDeque<>();

    @Override
    public void add(String observation) {
      queue.add(observation);
    }

    @Override
    public Optional<String> poll() {
      return Optional.ofNullable(queue.poll());
    }
  }

  private static final int ITERATIONS = 10;

  /**
   * Repeated {@value #ITERATIONS} times, not run once: a genuine thread race is not guaranteed to
   * land in its narrowest window on any single attempt, so one clean pass is weak evidence — ten
   * independent grants, each raced the same way, is the confidence this claim needs.
   */
  @Test
  void twoConcurrentDrainsOfTheSameGrantExecuteTheToolExactlyOnce() throws Exception {
    var mapper = TestMappers.plainlyPinned();
    var substrate = new InMemorySubstrate();
    var memory = new SubstrateMemory(substrate, "test-scope", mapper);
    var store = new SubstrateAgentPhaseStore(substrate, "test-scope", Clock.systemUTC(), mapper);
    var testType = AgentType.of("test");
    var approvalClient = TestApprovalClients.client(Kinds.approval(testType), mapper);
    var toolClient = TestToolClients.client(Kinds.tool(testType), mapper);
    var notifications = new CopyOnWriteArrayList<ComputationId>();
    Approver approver =
        context -> ApprovalOutcome.deferred((id, deadline) -> notifications.add(id), TERM);
    var tool = new CountingTool();
    var registry = ToolRegistry.of(ToolGrant.grant(tool, approver));
    var pump = new PumpedExecutor();
    var narrator = new RecordingTurnObserver();
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    List<List<ModelEvent>> script = new java.util.ArrayList<>();
    for (int i = 0; i < ITERATIONS; i++) {
      script.add(List.of(new ModelEvent.ToolUseEmitted(call, null)));
      script.add(List.of(new ModelEvent.TextChunk("done")));
    }
    var provider = new ScriptedModel(script);
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("test"),
            AgentId.of("test-scope"),
            narrator,
            pump,
            approvalClient,
            toolClient,
            mapper,
            ObservationRegistry.NOOP,
            () -> null,
            APPROVAL_CEILING,
            TOOL_CEILING);
    var harness =
        TestAgents.<String>harness(
            memory,
            store,
            new QueueBacklog(),
            text -> List.of(new TextBlock(text)),
            new ProviderModelCallExecutor(
                provider,
                TestSettings.SYSTEM_PROMPT,
                TestSettings.settings(),
                registry,
                memory,
                narrator,
                pump,
                ObservationRegistry.NOOP,
                () -> null),
            executor,
            HarnessObserver.noop(),
            false,
            StalenessPolicy.never());
    var agent = harness.bind(AgentId.of("test-scope"));
    // A real, multi-threaded executor for nudge()'s own submitted drain passes — nudge() no
    // longer runs the approval/tool drain on the caller's thread (continuum-adoption spec §7), so
    // the two racing nudge() calls below now merely SUBMIT work here; this pool is what actually
    // runs it, on its own thread(s), same as production's shared ComputationScheduler would.
    ExecutorService nudgeExecutor = Executors.newFixedThreadPool(2);
    var worker =
        new DeliveryWorker<String>(
            substrate, mapper, harness, (t, i) -> agent, nudgeExecutor, approvalClient, toolClient);

    for (int i = 0; i < ITERATIONS; i++) {
      agent.tell("go");
      pump.pumpUntilQuiet();
      assertThat(notifications).hasSize(i + 1);
      ComputationId parked = notifications.get(i);

      // Answer it: the completion creates the delivery both racers will drain.
      approvalClient.complete(ContinuumIds.continuumId(parked.value()), Approval.approved());

      ExecutorService pool = Executors.newFixedThreadPool(2);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch go = new CountDownLatch(1);
      Runnable drain =
          () -> {
            ready.countDown();
            try {
              go.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            worker.nudge();
          };
      var f1 = pool.submit(drain);
      var f2 = pool.submit(drain);
      ready.await();
      go.countDown();
      f1.get(10, TimeUnit.SECONDS);
      f2.get(10, TimeUnit.SECONDS);
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

      // nudge() only submits now (continuum-adoption spec §7): f1/f2 above confirm the two racing
      // SUBMISSIONS returned, not that nudgeExecutor's own threads finished running them, or that
      // the follow-up model call they dispatch onto `pump` has landed there yet — so this awaits
      // the turn's own resumption (Idle) rather than assuming one pumpUntilQuiet() call already
      // caught work a background thread had not enqueued yet.
      int expected = i + 1;
      long deadline = System.currentTimeMillis() + 10_000;
      while (!(store.load().value() instanceof AgentPhase.Idle)
          && System.currentTimeMillis() < deadline) {
        pump.pumpUntilQuiet();
        Thread.sleep(20);
      }

      assertThat(tool.invocations).hasValue(expected); // exactly one more, never two more
      assertThat(substrate.keys("outbox", 10)).isEmpty();
      assertThat(store.load().value()).isEqualTo(new AgentPhase.Idle());
    }
    nudgeExecutor.shutdown();
    assertThat(nudgeExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
  }
}
