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
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.CodecFactory;
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
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.agent.AgentType;
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
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * {@code nessy.fold} (in-the-loop amendment §2, §3): load, handle, remember, CAS save — the span
 * that replaces the JDBC library the 2026-08-26 soak imported and then had to remove.
 *
 * <p>Its DURATION is the store write plus the reduce plus the remembrance, so a slow CAS reads as a
 * slow fold in the round it happened. Its SCOPE is what the soak proved missing: a store that
 * records its own observation lands under the fold rather than as one of 199 roots. And a lost CAS
 * race produces a SECOND fold span rather than one long one, so a retried fold is legible in the
 * trace instead of only in a counter.
 */
class FoldSpanTest {

  /** Any deadline: these tests are about routing, not about when a wait ends. */
  private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

  private static final AgentType TYPE = AgentType.of("test");
  private static final AgentId SCOPE = AgentId.of("prod-eu");
  private static final ToolCall RESTART =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

  /** What a wrapped {@code DataSource} would record from inside the store's own write. */
  private static final String PROBE = "store.write";

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final TestObservationRegistry registry = TestObservationRegistry.create();
  private final InMemorySubstrate underlying = new InMemorySubstrate();

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  private List<Observation.Context> contexts() {
    List<Observation.Context> captured = new ArrayList<>();
    assertThat(registry).hasHandledContextsThatSatisfy(captured::addAll);
    return captured;
  }

  private List<Observation.Context> named(String name) {
    return contexts().stream().filter(context -> name.equals(context.getName())).toList();
  }

  private static String parentNameOf(Observation.Context context) {
    var parent = context.getParentObservation();
    return parent == null ? null : parent.getContextView().getName();
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

  /** Stands in for any third-party store instrumentation: one observation per document write. */
  private final class ProbingSubstrate implements Substrate {

    private final Substrate delegate;

    private ProbingSubstrate(Substrate delegate) {
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public Optional<Document> read(String kind, String key) {
      return delegate.read(kind, key);
    }

    @Override
    public void write(String kind, String key, byte[] payload, long expectedVersion) {
      Observation.createNotStarted(PROBE, registry).contextualName(PROBE).start().stop();
      delegate.write(kind, key, payload, expectedVersion);
    }

    @Override
    public void delete(String kind, String key, long expectedVersion) {
      delegate.delete(kind, key, expectedVersion);
    }

    @Override
    public List<String> keys(String kind, int limit) {
      return delegate.keys(kind, limit);
    }

    @Override
    public void append(String kind, String key, long expectedSeq, byte[] payload) {
      delegate.append(kind, key, expectedSeq, payload);
    }

    @Override
    public List<Substrate.Entry> entries(String kind, String key, long fromSeq) {
      return delegate.entries(kind, key, fromSeq);
    }

    @Override
    public long head(String kind, String key) {
      return delegate.head(kind, key);
    }

    @Override
    public void batch(List<Substrate.Op> ops) {
      delegate.batch(ops);
    }

    @Override
    public CodecFactory codecs() {
      return delegate.codecs();
    }
  }

  /**
   * The other writer, bumping the scope's version on the {@code nth} remembrance and never again.
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
            new SubstrateAgentPhaseStore(underlying, SCOPE.value(), Clock.systemUTC(), mapper);
        Versioned<AgentPhase> current = contender.load();
        contender.save(new Versioned<>(current.value(), current.version()));
      }
    }

    @Override
    public Context recall() {
      return Context.of(List.of());
    }
  }

  private static Memory forgetful() {
    return new Memory() {
      @Override
      public void remember(Remembrance remembrance) {
        // nothing to remember: these tests are about the fold's span, not its transcript
      }

      @Override
      public Context recall() {
        return Context.of(List.of());
      }
    };
  }

  private static ModelOutcome answered() {
    return new ModelOutcome.Responded(
        List.of(new TextBlock("done")), List.of(), ModelResponseId.of("r1"));
  }

  private DefaultHarness<String> shellOver(Substrate substrate, Memory memory) {
    DefaultHarness<String> harness =
        TestAgents.harness(
            TYPE,
            memory,
            new SubstrateAgentPhaseStore(substrate, SCOPE.value(), Clock.systemUTC(), mapper),
            new QueueBacklog(),
            text -> List.of(new TextBlock(text)),
            sink -> sink.deliver(new AgentEvent.ModelFinished(answered())),
            new NoToolsExecutor(),
            HarnessObserver.noop(),
            true,
            StalenessPolicy.never(),
            registry);
    HarnessTeardown.track(harness);
    return harness;
  }

  @Nested
  class TheScopeItHolds {

    /**
     * The test that would have caught the flat JDBC spans. A store that records its own observation
     * during the fold's CAS write must land INSIDE the fold, not beside it: the whole point of §2
     * is that a span which is current for the duration of the work is an ancestor of what the work
     * does.
     */
    @Test
    void a_store_observation_recorded_during_a_fold_is_a_child_of_the_fold_span() {
      shellOver(new ProbingSubstrate(underlying), forgetful()).bind(SCOPE).tell("restart prod-eu");

      List<Observation.Context> probes = named(PROBE);
      assertThat(probes).isNotEmpty();
      assertThat(probes)
          .allSatisfy(probe -> assertThat(parentNameOf(probe)).isEqualTo(Observations.FOLD));
    }
  }

  @Nested
  class ARetriedFold {

    /** Two attempts, two spans — never one long one, which would hide the contention entirely. */
    @Test
    void a_cas_conflict_in_the_shell_produces_a_second_fold_span() {
      shellOver(underlying, new SecondWriter(2)).bind(SCOPE).tell("restart prod-eu");

      // The observation's fold, then the model fold's losing attempt, then its winning one.
      assertThat(named(Observations.FOLD)).hasSize(3);
      assertThat(named(Observations.FOLD))
          .filteredOn(
              fold ->
                  "retried"
                      .equals(fold.getLowCardinalityKeyValue(Observations.FOLD_OUTCOME).getValue()))
          .hasSize(1);
    }

    /** The uncontended control: the same turn, one attempt per fold. */
    @Test
    void an_uncontended_turn_produces_one_fold_span_per_event() {
      shellOver(underlying, forgetful()).bind(SCOPE).tell("restart prod-eu");

      assertThat(named(Observations.FOLD)).hasSize(2);
    }
  }

  @Nested
  class InTheDeliveryWorker {

    private final Continuum continuum =
        new DefaultContinuum(new InMemoryContinuumRepository(), Clock.systemUTC());
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

    private void parkOn(SubstrateAgentPhaseStore store, ComputationId computation) {
      Message turn = Message.assistant(List.of(new ToolUseBlock(RESTART)));
      AgentPhase phase =
          new AgentPhase.AwaitingTools(
              turn,
              Map.of("c1", new ToolCallPhase.AwaitingResult(computation)),
              ModelResponseId.of("r1"));
      store.save(new Versioned<>(phase, store.load().version()));
    }

    /** The worker's own arm of the retry: a lost CAS is a second span there too. */
    @Test
    void a_cas_conflict_in_the_worker_produces_a_second_fold_span() {
      var store =
          new SubstrateAgentPhaseStore(underlying, SCOPE.value(), Clock.systemUTC(), mapper);
      DefaultHarness<String> harness =
          TestAgents.harness(
              TYPE,
              new SecondWriter(1),
              store,
              new QueueBacklog(),
              text -> List.of(new TextBlock(text)),
              sink -> {},
              new NoToolsExecutor(),
              HarnessObserver.noop(),
              false,
              StalenessPolicy.never(),
              registry);
      HarnessTeardown.track(harness);
      Agent<String> agent = harness.bind(SCOPE);
      var worker =
          new DeliveryWorker<>(
              underlying,
              mapper,
              harness,
              (type, id) -> agent,
              new PumpedExecutor(),
              approvalClient,
              toolClient);
      var created = toolClient.create(new Routing(TYPE.name(), SCOPE.value(), "r1", RESTART));
      parkOn(store, ComputationId.of(created.id().value().toString()));
      toolClient.complete(created.id(), ToolResult.ok("restarted"));

      worker.drainTools(BatchSize.of(10));

      assertThat(named(Observations.FOLD)).hasSize(2);
      assertThat(store.load().value()).isInstanceOf(AgentPhase.AwaitingModel.class);
    }
  }

  /**
   * A lost CAS race is engine-health noise, not a fold failure: a real Tempo run showed THREE
   * {@code STATUS_CODE_ERROR} spans in a single healthy round, because a turn with several parallel
   * tool calls contends on the scope's single state document by design. Only a genuine fold failure
   * — one that leaves the store, the reducer or the memory in a state the retry loop cannot
   * converge past on its own — should render as an error.
   */
  @Nested
  class TheFoldOutcome {

    /**
     * The retried attempt is healthy, contended behaviour — not a failure the trace should flag.
     */
    @Test
    void a_retried_fold_is_recorded_ok_with_a_retried_outcome() {
      shellOver(underlying, new SecondWriter(2)).bind(SCOPE).tell("restart prod-eu");

      List<Observation.Context> retried =
          named(Observations.FOLD).stream()
              .filter(
                  fold ->
                      "retried"
                          .equals(
                              fold.getLowCardinalityKeyValue(Observations.FOLD_OUTCOME).getValue()))
              .toList();

      assertThat(retried).isNotEmpty();
      assertThat(retried).allSatisfy(fold -> assertThat(fold.getError()).isNull());
    }

    /** A genuine failure inside the fold — not a lost CAS race — still records as an error. */
    @Test
    void a_fold_whose_memory_throws_is_recorded_as_an_error() {
      Memory explosive =
          new Memory() {
            @Override
            public void remember(Remembrance remembrance) {
              throw new IllegalStateException("boom");
            }

            @Override
            public Context recall() {
              return Context.of(List.of());
            }
          };
      Agent<String> agent = shellOver(underlying, explosive).bind(SCOPE);

      assertThatThrownBy(() -> agent.tell("restart prod-eu"))
          .isInstanceOf(IllegalStateException.class);

      List<Observation.Context> errored =
          named(Observations.FOLD).stream().filter(fold -> fold.getError() != null).toList();

      assertThat(errored).isNotEmpty();
      assertThat(errored)
          .allSatisfy(
              fold ->
                  assertThat(fold.getLowCardinalityKeyValue(Observations.ERROR_TYPE).getValue())
                      .isEqualTo("IllegalStateException"));
    }
  }
}
