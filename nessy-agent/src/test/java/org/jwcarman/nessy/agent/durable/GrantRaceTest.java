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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
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
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The double-drain race (durable-deliveries spec §5a invariant 5, fix round 2 item (c)): two
 * concurrent drains of the SAME grant delivery — {@link DeliveryWorker#nudge()} racing the
 * heartbeat, modeled here as two threads both calling {@code nudge()} at once — must execute the
 * granted tool exactly once. Before the fix, both racers would read the delivery present, both call
 * the tool, and both attempt to fold — a duplicate external side effect with no crash involved. A
 * version-bump claim was tried first and proven insufficient by this exact test (a second racer
 * reading after the first's bump sees an ordinary document at a newer version and bumps it again
 * just as validly); the actual single-winner mechanism is {@link DeliveryWorker}'s in-process
 * {@code claiming} key set — that is what makes this test pass: only the claim's winner ever
 * reaches the tool.
 */
class GrantRaceTest {

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
    var store = new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
    var backend = new SubstrateComputations(substrate, mapper);
    var notifications = new CopyOnWriteArrayList<ApprovalRequest>();
    var approver = new ComputationApprover(backend, notifications::add, mapper);
    var deferredPolicy = new ComputationDeferredToolCallPolicy(backend, mapper);
    var tool = new CountingTool();
    var registry = ToolRegistry.of(ToolGrant.grant(tool, UsagePolicy.requireApproval()));
    var pump = new PumpedExecutor();
    var narrator = new RecordingTurnObserver();
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    List<List<ModelEvent>> script = new java.util.ArrayList<>();
    for (int i = 0; i < ITERATIONS; i++) {
      script.add(List.of(new ModelEvent.ToolUseEmitted(call, null)));
      script.add(List.of(new ModelEvent.TextChunk("done")));
    }
    var provider = new ScriptedModelProvider(script);
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("test"),
            AgentId.of("test-scope"),
            narrator,
            pump,
            deferredPolicy,
            approver,
            mapper);
    var harness =
        TestAgents.<String>harness(
            memory,
            store,
            new QueueBacklog(),
            text -> List.of(new TextBlock(text)),
            new ProviderModelCallExecutor(
                provider, TestSettings.settings(), registry, memory, narrator, pump),
            executor,
            AgentObserver.noop(),
            false,
            StalenessPolicy.never());
    var agent = new DefaultAgent<String>(harness, harness.binding(AgentId.of("test-scope")));
    var worker =
        new DeliveryWorker<String>(
            substrate, mapper, harness, (t, i) -> agent, java.time.Duration.ofHours(1));

    for (int i = 0; i < ITERATIONS; i++) {
      agent.observe("go");
      pump.pumpUntilQuiet();
      assertThat(notifications).hasSize(i + 1);
      ApprovalRequest request = notifications.get(i);

      // Grant it: the ownership transfer creates the outbox delivery both racers will drain.
      backend.complete(request.address().approval(), DurableDecisions.granted());

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

      pump.pumpUntilQuiet();

      assertThat(tool.invocations).hasValue(i + 1); // exactly one more, never two more
      assertThat(substrate.keys("outbox", 10)).isEmpty();
      assertThat(store.load().phase()).isEqualTo(new Phase.Idle());
    }
  }
}
