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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.slf4j.LoggerFactory;

/**
 * The mismatched-delivery rule (James's 2026-08-25 ruling, approval-lifecycle spec §3, §4, §10): a
 * delivery whose scope is not in the status that awaits it is a PERMANENT failure, never a race
 * worth retrying — so the worker DROPS it with a WARN naming the agent, the call, the computation
 * and the status it found, and CONSUMES the delivery rather than releasing it for redelivery. This
 * replaces the retired release-and-redeliver behaviour (and its {@code EarlyDeliveryException}):
 * {@code ComputationApprovalContext.defer()} folds {@code AwaitingApproval} and commits BEFORE it
 * returns the id, so no approval answer can outrun its own park — anything that arrives against a
 * {@code Pending} call is an orphan or a duplicate, and never gets better. The tool side is no
 * exception: a {@code ToolFinished} against a still-{@code Running} call names a computation the
 * scope knows nothing of, and is dropped the same way.
 *
 * <p>The capturing appender is wired onto {@link DeliveryWorker}'s own class logger, the technique
 * {@code DeliveryWorkerSilentLossWarningTest} uses.
 */
class DeliveryWorkerMismatchedDeliveryTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
  private static final Duration PAST_THE_BACKOFF = Duration.ofSeconds(6);

  private static final class NoopBacklog implements Backlog<String> {
    @Override
    public void add(String observation) {
      // fixture only: never exercised by this test
    }

    @Override
    public Optional<String> poll() {
      return Optional.empty();
    }
  }

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final InMemorySubstrate substrate = new InMemorySubstrate();
  private final TestClock clock = new TestClock(Instant.parse("2026-08-25T00:00:00Z"));
  private final Continuum continuum =
      new DefaultContinuum(new InMemoryContinuumRepository(), clock);
  private final SubstrateAgentStateStore store =
      new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
  private final ContinuumClient<Approval, ApprovalRouting> approvalClient =
      continuum.client(
          "approval/test",
          Approval.class,
          ApprovalRouting.class,
          cfg ->
              cfg.resultCodec(ApprovalCodec.codec(mapper))
                  .continuationCodec(ApprovalRouting.codec(mapper))
                  .deadline(Duration.ofDays(7)));
  private final ContinuumClient<ToolResult, Routing> toolClient =
      continuum.client(
          "tool/test",
          ToolResult.class,
          Routing.class,
          cfg ->
              cfg.resultCodec(TestToolClients.toolResultCodec(mapper))
                  .continuationCodec(Routing.codec(mapper))
                  .deadline(Duration.ofHours(1)));
  private final Harness<String> harness =
      TestAgents.<String>harness(
          new VerbatimMemory(),
          store,
          new NoopBacklog(),
          text -> List.of(),
          sink -> {},
          new NoToolsExecutor(),
          AgentObserver.noop(),
          false,
          StalenessPolicy.never());
  private final Agent<String> agent = harness.bind(AgentId.of("test-scope"));
  private final DeliveryWorker<String> worker =
      new DeliveryWorker<>(
          substrate,
          mapper,
          harness,
          (type, id) -> agent,
          new PumpedExecutor(),
          approvalClient,
          toolClient);

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void wires_a_capturing_appender_onto_delivery_workers_own_logger() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(DeliveryWorker.class);
    classicLogger.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.start();
    classicLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(DeliveryWorker.class);
    classicLogger.detachAppender(appender);
    classicLogger.setLevel(null);
  }

  private void scopeWith(CallStatus status) {
    Message turn = Message.assistant(List.of(new ToolUseBlock(CALL)));
    Phase phase = new Phase.AwaitingTools(turn, Map.of("c1", status), ModelResponseId.of("r1"));
    store.save(new State(phase, store.load().version()));
  }

  private CallStatus status() {
    return ((Phase.AwaitingTools) store.load().phase()).calls().get("c1");
  }

  private ApprovalRequest request() {
    return ApprovalRequest.draft("test", "test-scope", CALL, mapper).freeze();
  }

  private ComputationId answerAnApproval() {
    var created =
        approvalClient.create(
            new ApprovalRouting(new Routing("test", "test-scope", "r1", CALL), request()));
    approvalClient.complete(created.id(), Approval.approved());
    return ComputationId.of(created.id().value().toString());
  }

  private List<ILoggingEvent> warnings() {
    return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }

  @Test
  void an_answer_for_a_still_pending_call_is_dropped_with_a_warning_and_never_redelivered() {
    scopeWith(new CallStatus.Pending());
    ComputationId orphan = answerAnApproval();

    worker.drainApprovals(BatchSize.of(10));

    assertThat(status()).isInstanceOf(CallStatus.Pending.class);
    assertThat(warnings()).hasSize(1);
    assertThat(warnings().getFirst().getFormattedMessage())
        .contains("test-scope")
        .contains("c1")
        .contains(orphan.value())
        .contains("Pending");

    // consumed, not released: nothing comes back after the backoff
    clock.advance(PAST_THE_BACKOFF);

    assertThat(worker.drainApprovals(BatchSize.of(10))).isZero();
  }

  @Test
  void an_answer_naming_a_computation_the_parked_call_does_not_is_dropped_with_a_warning() {
    scopeWith(new CallStatus.AwaitingApproval(ComputationId.of("parked-elsewhere"), request()));
    answerAnApproval(); // an orphan: a different computation entirely

    worker.drainApprovals(BatchSize.of(10));

    assertThat(status()).isInstanceOf(CallStatus.AwaitingApproval.class);
    assertThat(warnings()).hasSize(1);
    assertThat(warnings().getFirst().getFormattedMessage()).contains("AwaitingApproval");

    clock.advance(PAST_THE_BACKOFF);

    assertThat(worker.drainApprovals(BatchSize.of(10))).isZero();
  }

  @Test
  void a_result_naming_a_computation_the_parked_call_does_not_is_dropped_with_a_warning() {
    scopeWith(new CallStatus.AwaitingResult(ComputationId.of("parked-elsewhere")));
    var created = toolClient.create(new Routing("test", "test-scope", "r1", CALL));
    toolClient.complete(created.id(), ToolResult.ok("done"));

    worker.drainTools(BatchSize.of(10));

    assertThat(status()).isInstanceOf(CallStatus.AwaitingResult.class);
    assertThat(warnings()).hasSize(1);
    assertThat(warnings().getFirst().getFormattedMessage()).contains("AwaitingResult");

    clock.advance(PAST_THE_BACKOFF);

    assertThat(worker.drainTools(BatchSize.of(10))).isZero();
  }

  /**
   * The rule has no exception on the tool side either: a result reaching a call still {@code
   * Running} — before its own {@code ToolDeferred} has folded — names a computation the scope knows
   * nothing of, so it is dropped with a WARN like any other mismatch. In practice this window does
   * not open (§4): the executor mints the computation on the {@code Awaited.Deferred} arm, right
   * after the tool body returns, and the very next statement on that same thread folds {@code
   * ToolDeferred} — with a one-day default deadline, and with the phase the only handle to the id,
   * nothing can complete or expire it inside a single thread hop.
   */
  @Test
  void a_result_for_a_call_still_running_is_dropped_with_a_warning() {
    scopeWith(new CallStatus.Running());
    var created = toolClient.create(new Routing("test", "test-scope", "r1", CALL));
    toolClient.complete(created.id(), ToolResult.ok("done"));

    worker.drainTools(BatchSize.of(10));

    assertThat(status()).isInstanceOf(CallStatus.Running.class);
    assertThat(warnings()).hasSize(1);
    assertThat(warnings().getFirst().getFormattedMessage())
        .contains("test-scope")
        .contains("c1")
        .contains(created.id().value().toString())
        .contains("Running");

    clock.advance(PAST_THE_BACKOFF);

    assertThat(worker.drainTools(BatchSize.of(10))).isZero();
  }

  /**
   * The scope is in {@code AwaitingTools}, but for a DIFFERENT turn's calls — the delivery names a
   * call id the phase has no status for at all. The WARN says so rather than rendering a null.
   */
  @Test
  void a_delivery_for_a_call_the_phase_has_no_status_for_says_no_such_call() {
    ToolCall other = new ToolCall("c2", "other", JsonNodeFactory.instance.objectNode());
    Message turn = Message.assistant(List.of(new ToolUseBlock(other)));
    Phase phase =
        new Phase.AwaitingTools(
            turn, Map.of("c2", new CallStatus.Running()), ModelResponseId.of("r1"));
    store.save(new State(phase, store.load().version()));
    answerAnApproval(); // routed to call "c1", which this phase does not hold

    worker.drainApprovals(BatchSize.of(10));

    assertThat(warnings()).hasSize(1);
    assertThat(warnings().getFirst().getFormattedMessage()).contains("no such call");
  }

  /**
   * The scope is not in {@code AwaitingTools} at all — the turn finished long ago. There is no
   * per-call status to name, so the WARN names the phase itself.
   */
  @Test
  void a_delivery_reaching_a_scope_that_is_no_longer_awaiting_tools_names_the_phase() {
    store.save(new State(new Phase.Idle(), store.load().version()));
    answerAnApproval();

    worker.drainApprovals(BatchSize.of(10));

    assertThat(warnings()).hasSize(1);
    assertThat(warnings().getFirst().getFormattedMessage()).contains("status=Idle");
  }
}
