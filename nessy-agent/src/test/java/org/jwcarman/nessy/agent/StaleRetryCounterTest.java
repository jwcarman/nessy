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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.store.SubstrateAgentPhaseStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * {@code nessy.state.stale_retries} (agentic-o11y spec §1.2): every lost CAS race, at both fold
 * sites. This counter is the one that says whether a deployment is quietly burning its throughput
 * on contention — a scope written by two nodes at once still converges, silently, so without a
 * number nobody finds out until latency does.
 *
 * <p>Every race here is a REAL one: a second writer lands a write on the same scope between the
 * fold's read and its CAS, from inside {@link Memory#remember} — the one hook that runs at exactly
 * that instant in both the shell's fold and the worker's. Nothing is stubbed to throw.
 */
class StaleRetryCounterTest {

  private static final AgentType TYPE = AgentType.of("test");
  private static final AgentId SCOPE = AgentId.of("prod-eu");
  private static final ToolCall RESTART =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final TestObservationRegistry registry = TestObservationRegistry.create();
  private final InMemorySubstrate substrate = new InMemorySubstrate();
  private final SubstrateAgentPhaseStore store =
      new SubstrateAgentPhaseStore(substrate, SCOPE.value(), Clock.systemUTC(), mapper);
  private final AtomicLong staleRetryEvents = new AtomicLong();

  @BeforeEach
  void countEvents() {
    registry.observationConfig().observationHandler(eventCounter());
  }

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  private static final class QueueBacklog implements Backlog<String> {

    private final Deque<String> queue = new ArrayDeque<>();

    @Override
    public void add(String observation) {
      queue.add(observation);
    }

    @Override
    public Optional<String> poll() {
      return Optional.ofNullable(queue.poll());
    }
  }

  /**
   * The other writer. Every fold — the shell's and the worker's alike — reads the scope, remembers
   * what the fold implies, then CAS-writes; so remembering is the exact instant at which a
   * competing write costs the fold its race. This one bumps the scope's version (rewriting the
   * phase it already holds, so the retry re-handles against unchanged state) on the {@code nth}
   * remembrance and never again.
   */
  private final class SecondWriter implements Memory {

    private final AtomicInteger countdown;

    private SecondWriter(int nth) {
      this.countdown = new AtomicInteger(nth);
    }

    @Override
    public void remember(Remembrance remembrance) {
      if (countdown.decrementAndGet() == 0) {
        var contender =
            new SubstrateAgentPhaseStore(substrate, SCOPE.value(), Clock.systemUTC(), mapper);
        Versioned<AgentPhase> current = contender.load();
        contender.save(new Versioned<>(current.value(), current.version()));
      }
    }

    @Override
    public Context recall() {
      return Context.of(List.of());
    }
  }

  /**
   * Every stale retry this run recorded, in BOTH shapes the counter can take (soak finding F2,
   * 2026-08-26): a span event on the round's own segment when one is open — which is the usual
   * case, and the whole point of the fix — and a standalone zero-duration observation when the
   * scope has no segment to hang it on. The assertions below are unchanged; only where the count
   * lives moved.
   */
  private long staleRetriesRecorded() {
    List<Observation.Context> captured = new ArrayList<>();
    assertThat(registry).hasHandledContextsThatSatisfy(captured::addAll);
    long asObservations =
        captured.stream()
            .filter(context -> Observations.STALE_RETRIES.equals(context.getName()))
            .count();
    return asObservations + staleRetryEvents.get();
  }

  /** Counts {@code onEvent}, which neither the context nor the TCK's assertions expose. */
  private ObservationHandler<Observation.Context> eventCounter() {
    return new ObservationHandler<>() {
      @Override
      public boolean supportsContext(Observation.Context context) {
        return true;
      }

      @Override
      public void onEvent(Observation.Event event, Observation.Context context) {
        if (Observations.STALE_RETRIES.equals(event.getName())) {
          staleRetryEvents.incrementAndGet();
        }
      }
    };
  }

  @Nested
  class InTheShell {

    private Harness<String> harnessWith(Memory memory) {
      return TestAgents.harness(
          TYPE,
          memory,
          store,
          new QueueBacklog(),
          text -> List.of(new TextBlock(text)),
          sink -> sink.deliver(new AgentEvent.ModelFinished(answered())),
          new NoToolsExecutor(),
          HarnessObserver.noop(),
          true,
          StalenessPolicy.never(),
          registry);
    }

    private ModelOutcome answered() {
      return new ModelOutcome.Responded(
          List.of(new TextBlock("done")), List.of(), ModelResponseId.of("r1"));
    }

    /**
     * The drain arm ({@code DefaultAgent#drainOne}): losing the race on an {@code Observed} fold
     * puts the observation back on the backlog, and the drain loop picks it straight back up.
     */
    @Test
    void an_observation_that_loses_the_idle_race_counts_one_stale_retry() {
      Harness<String> harness = harnessWith(new SecondWriter(1));

      harness.bind(SCOPE).tell("restart prod-eu");

      assertThat(staleRetriesRecorded()).isEqualTo(1);
      assertThat(store.load().value()).isInstanceOf(AgentPhase.Idle.class);
    }

    /**
     * The commit arm ({@code DefaultAgent#commit}): the second remembrance is the assistant turn,
     * so the model's own {@code ModelFinished} fold is the one that loses, and {@code commit}'s
     * {@code while(true)} re-handles it against fresh state.
     */
    @Test
    void a_model_fold_that_loses_the_race_counts_one_stale_retry_and_still_commits() {
      Harness<String> harness = harnessWith(new SecondWriter(2));

      harness.bind(SCOPE).tell("restart prod-eu");

      assertThat(staleRetriesRecorded()).isEqualTo(1);
      assertThat(store.load().value()).isInstanceOf(AgentPhase.Idle.class);
    }
  }

  @Nested
  class InTheDeliveryWorker {

    private final TestClock clock = new TestClock(Instant.parse("2026-08-26T00:00:00Z"));
    private final Continuum continuum =
        new DefaultContinuum(new InMemoryContinuumRepository(), clock);
    private final ContinuumClient<Approval, ApprovalRouting> approvalClient =
        TestApprovalClients.client("approval/test", mapper);
    private final ContinuumClient<ToolResult, Routing> toolClient =
        continuum.client(
            "tool/test",
            ToolResult.class,
            Routing.class,
            cfg ->
                cfg.resultCodec(TestToolClients.toolResultCodec(mapper))
                    .continuationCodec(Routing.codec(mapper))
                    .deadline(Duration.ofHours(1)));

    /** A scope parked on one deferred tool call, awaiting exactly the computation below. */
    private void parkOn(ComputationId computation) {
      Message turn = Message.assistant(List.of(new ToolUseBlock(RESTART)));
      AgentPhase phase =
          new AgentPhase.AwaitingTools(
              turn,
              Map.of("c1", new ToolCallState.AwaitingResult(computation)),
              ModelResponseId.of("r1"));
      store.save(new Versioned<>(phase, store.load().version()));
    }

    /**
     * The worker's own arm ({@code DeliveryWorker#fold}): a delivered tool result folds through
     * {@code ToolFoldRemembrance}, which remembers before the CAS — so the second writer lands
     * inside that window and the {@code ConflictException} arm counts, re-reads, and converges.
     */
    @Test
    void a_delivery_that_loses_the_race_counts_one_stale_retry_and_still_folds() {
      var memory = new SecondWriter(1);
      Harness<String> harness =
          TestAgents.harness(
              TYPE,
              memory,
              store,
              new QueueBacklog(),
              text -> List.of(new TextBlock(text)),
              sink -> {},
              new NoToolsExecutor(),
              HarnessObserver.noop(),
              false,
              StalenessPolicy.never(),
              registry);
      Agent<String> agent = harness.bind(SCOPE);
      var worker =
          new DeliveryWorker<>(
              substrate,
              mapper,
              harness,
              (type, id) -> agent,
              new PumpedExecutor(),
              approvalClient,
              toolClient);
      var created = toolClient.create(new Routing(TYPE.name(), SCOPE.value(), "r1", RESTART));
      parkOn(ComputationId.of(created.id().value().toString()));
      toolClient.complete(created.id(), ToolResult.ok("restarted"));

      worker.drainTools(BatchSize.of(10));

      assertThat(staleRetriesRecorded()).isEqualTo(1);
      // Converged: the call is finished and the turn moved on to the model.
      assertThat(store.load().value()).isInstanceOf(AgentPhase.AwaitingModel.class);
    }
  }
}
