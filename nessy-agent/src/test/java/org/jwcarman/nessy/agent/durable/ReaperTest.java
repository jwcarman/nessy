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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentResolver;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RaceOnceOnWriteSubstrate;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The reaper (durable-deliveries spec §6): the worker's second sweep over {@code computation}
 * documents. Deadline-less computations wait forever; a {@code RETRYABLE} overdue computation is
 * bumped and redispatched under the same {@code ToolInvocationId}; a {@code NON_RETRYABLE} overdue
 * computation is failed and rides the normal delivery pipeline into the fold. {@link
 * DeliveryWorker#reapOnce()} is called directly (package-visible, same package) rather than through
 * the real-time heartbeat, so these stay deterministic.
 */
class ReaperTest {

  /**
   * Fix round 1, item 5: reclaims every harness this test class built (directly or via {@link
   * org.jwcarman.nessy.agent.support.TestAgents} / {@code AgentFixture}) — each now owns a live
   * delivery-worker heartbeat (harness-first spec §4) that nothing else stops.
   */
  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  private static final ToolCall CALL =
      new ToolCall("c1", "durable_op", JsonNodeFactory.instance.objectNode());

  record NoInput() {}

  /** Records every invocation's {@link ToolInvocationId} and never completes on its own. */
  private static final class RecordingParkingTool implements Tool<NoInput> {
    private final List<ToolInvocationId> invocations = new CopyOnWriteArrayList<>();
    private final RetrySemantics retrySemantics;
    private final Optional<Duration> timeout;

    RecordingParkingTool(RetrySemantics retrySemantics, Optional<Duration> timeout) {
      this.retrySemantics = retrySemantics;
      this.timeout = timeout;
    }

    @Override
    public String name() {
      return "durable_op";
    }

    @Override
    public String description() {
      return "parks forever unless the reaper acts on it";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      invocations.add(context.invocationId());
      return Awaited.deferred();
    }

    @Override
    public RetrySemantics retrySemantics() {
      return retrySemantics;
    }

    @Override
    public Optional<Duration> timeout() {
      return timeout;
    }
  }

  /**
   * Parks on the first invocation, answers {@link Awaited#ready} on every subsequent one — F2: a
   * {@code RETRYABLE} tool that answers immediately on redispatch must not orphan its own
   * computation.
   */
  private static final class RecordingParkThenReadyTool implements Tool<NoInput> {
    private final List<ToolInvocationId> invocations = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
      return "durable_op";
    }

    @Override
    public String description() {
      return "parks once, then answers immediately on redispatch";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      invocations.add(context.invocationId());
      if (invocations.size() == 1) {
        return Awaited.deferred();
      }
      return Awaited.ready(ToolResult.ok("done"));
    }

    @Override
    public RetrySemantics retrySemantics() {
      return RetrySemantics.RETRYABLE;
    }

    @Override
    public Optional<Duration> timeout() {
      return Optional.of(Duration.ofMillis(1));
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

  private record World(
      Substrate substrate,
      SubstrateAgentStateStore store,
      Harness<String> harness,
      Agent<String> agent,
      DeliveryWorker<String> worker,
      PumpedExecutor pump) {}

  /** A very long poll interval: the heartbeat thread never fires on its own in these tests. */
  private static World worldFor(Tool<NoInput> tool, Substrate substrate) {
    var mapper = TestMappers.plainlyPinned();
    var memory = new SubstrateMemory(substrate, "test-scope", mapper);
    var store = new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
    var backend = new SubstrateComputations(substrate, mapper);
    var narrator = new RecordingTurnObserver();
    var registry = ToolRegistry.of(tool);
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(CALL, null)),
                List.of(new ModelEvent.TextChunk("done"))));
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("test"),
            AgentId.of("test-scope"),
            narrator,
            pump,
            new ComputationDeferredToolCallPolicy(backend, mapper),
            mapper);
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
                pump),
            executor,
            AgentObserver.noop(),
            false,
            StalenessPolicy.never());
    var agent = harness.bind(AgentId.of("test-scope"));
    AgentResolver resolver = (t, i) -> agent;
    var worker =
        new DeliveryWorker<String>(substrate, mapper, harness, resolver, Duration.ofHours(1));
    return new World(substrate, store, harness, agent, worker, pump);
  }

  @Test
  void retryIdentityIsPreservedAcrossReaperRedispatch() throws InterruptedException {
    var tool =
        new RecordingParkingTool(RetrySemantics.RETRYABLE, Optional.of(Duration.ofMillis(1)));
    var world = worldFor(tool, new InMemorySubstrate());

    world.agent().observe("go");
    world.pump().pumpUntilQuiet();
    assertThat(tool.invocations).hasSize(1);
    Thread.sleep(5); // let the 1ms timeout genuinely elapse

    world.worker().reapOnce();
    world.pump().pumpUntilQuiet();
    world.worker().reapOnce();
    world.pump().pumpUntilQuiet();

    assertThat(tool.invocations).hasSizeGreaterThanOrEqualTo(2);
    Set<ToolInvocationId> distinct = Set.copyOf(tool.invocations);
    assertThat(distinct).hasSize(1); // every redispatch reuses the exact same invocation id
  }

  @Test
  void aNonRetryableOverdueComputationRidesTheNormalPipelineIntoTheFold()
      throws InterruptedException {
    var tool =
        new RecordingParkingTool(RetrySemantics.NON_RETRYABLE, Optional.of(Duration.ofMillis(1)));
    var world = worldFor(tool, new InMemorySubstrate());

    world.agent().observe("go");
    world.pump().pumpUntilQuiet();
    assertThat(tool.invocations).hasSize(1);
    Thread.sleep(5); // let the 1ms timeout genuinely elapse

    world.worker().reapOnce();
    world.pump().pumpUntilQuiet();

    assertThat(world.store().load().phase()).isEqualTo(new Phase.Idle());
    assertThat(tool.invocations).hasSize(1); // never redispatched: failed, not retried
  }

  @Test
  void aDeadlineLessComputationIsNeverReaped() {
    var tool = new RecordingParkingTool(RetrySemantics.RETRYABLE, Optional.empty());
    var world = worldFor(tool, new InMemorySubstrate());

    world.agent().observe("go");
    world.pump().pumpUntilQuiet();
    assertThat(tool.invocations).hasSize(1);

    world.worker().reapOnce();
    world.pump().pumpUntilQuiet();
    world.worker().reapOnce();
    world.pump().pumpUntilQuiet();
    world.worker().reapOnce();
    world.pump().pumpUntilQuiet();

    assertThat(tool.invocations).hasSize(1); // never redispatched
    assertThat(world.store().load().phase()).isInstanceOf(Phase.AwaitingTools.class);
  }

  @Test
  void aRacedDeadlineBumpBacksOffRatherThanDoubleDispatching() throws InterruptedException {
    var tool =
        new RecordingParkingTool(RetrySemantics.RETRYABLE, Optional.of(Duration.ofMillis(1)));
    var backing = new InMemorySubstrate();
    var world = worldFor(tool, backing);

    world.agent().observe("go");
    world.pump().pumpUntilQuiet();
    assertThat(tool.invocations).hasSize(1);
    Thread.sleep(5); // let the 1ms timeout genuinely elapse

    // the current computation payload stands in for a competing worker's own winning CAS bump
    String key = backing.keys("computation", 10).getFirst();
    byte[] currentPayload = backing.read("computation", key).orElseThrow().payload();
    var raced = new RaceOnceOnWriteSubstrate(backing, currentPayload);
    AgentResolver resolver = (t, i) -> world.agent();
    var racedWorker =
        new DeliveryWorker<String>(
            raced, TestMappers.plainlyPinned(), world.harness(), resolver, Duration.ofHours(1));

    racedWorker.reapOnce(); // the bump write loses the race and this sweep backs off silently
    world.pump().pumpUntilQuiet();

    assertThat(tool.invocations).hasSize(1); // no double dispatch past the lost CAS

    racedWorker.reapOnce(); // the next sweep is unraced and succeeds normally
    world.pump().pumpUntilQuiet();

    assertThat(tool.invocations).hasSize(2);
    assertThat(Set.copyOf(tool.invocations)).hasSize(1); // still the same invocation id
  }

  /**
   * F2: a {@code RETRYABLE} tool answering immediately on redispatch must not orphan its own
   * computation — the reaper completes it straight into the pipeline instead of leaving it behind
   * for an unbounded reap loop and an ever-growing computation table.
   */
  @Test
  void aRetryableToolThatAnswersImmediatelyOnRedispatchConsumesItsOwnComputation()
      throws InterruptedException {
    var tool = new RecordingParkThenReadyTool();
    var world = worldFor(tool, new InMemorySubstrate());

    world.agent().observe("go");
    world.pump().pumpUntilQuiet();
    assertThat(tool.invocations).hasSize(1);
    Thread.sleep(5); // let the 1ms timeout genuinely elapse

    var computations = new SubstrateComputations(world.substrate(), TestMappers.plainlyPinned());
    String key = world.substrate().keys("computation", 10).getFirst();

    world.worker().reapOnce(); // redispatches; the tool answers Ready this time
    world.pump().pumpUntilQuiet();

    assertThat(tool.invocations).hasSize(2);
    assertThat(computations.find(ComputationId.of(key))).isEmpty(); // consumed, not orphaned
    assertThat(world.store().load().phase()).isEqualTo(new Phase.Idle());

    world.worker().reapOnce(); // nothing left to reap
    world.pump().pumpUntilQuiet();

    assertThat(tool.invocations).hasSize(2); // not invoked a third time
  }

  /**
   * F2: {@code keys()} is lexicographic and {@code "approval:"} sorts before {@code "tool:"} — a
   * naive {@code keys(COMPUTATION_KIND, 1000)} fetch would let 1000+ pending approvals (deadline-
   * less, never reapable) fill the whole result and truncate every {@code "tool:"} key out of it,
   * starving the reaper of any real tool deadline behind them. This writes 1000 raw approval-
   * prefixed documents directly (skipping the full approval-gate flow, which would be far slower
   * for no additional coverage) plus one genuinely overdue tool computation, and checks the reaper
   * still reaches and reaps the latter.
   */
  @Test
  void aBacklogOf1000PendingApprovalsDoesNotStarveTheReaperOfAnOverdueToolComputation()
      throws InterruptedException {
    var tool =
        new RecordingParkingTool(RetrySemantics.NON_RETRYABLE, Optional.of(Duration.ofMillis(1)));
    var substrate = new InMemorySubstrate();
    var world = worldFor(tool, substrate);

    world.agent().observe("go");
    world.pump().pumpUntilQuiet();
    assertThat(tool.invocations).hasSize(1);
    Thread.sleep(5); // let the 1ms timeout genuinely elapse

    // 1000 deadline-less approval-prefixed documents; "approval:" sorts before "tool:", so a
    // narrow keys() fetch would let these alone crowd out the real tool computation below.
    for (int i = 0; i < 1000; i++) {
      String key = "approval:test:test-scope:r%04d:c1".formatted(i);
      substrate.write("computation", key, new byte[] {0}, 0);
    }

    world.worker().reapOnce();
    world.pump().pumpUntilQuiet();

    assertThat(world.store().load().phase()).isEqualTo(new Phase.Idle());
    assertThat(tool.invocations).hasSize(1); // never redispatched: failed, not retried
  }
}
