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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.TypedOutcome;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RaceOnceOnBatchSubstrate;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.agent.support.ThrowingThenDelegatingMemory;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The tool kind on Continuum (continuum-adoption spec §3, §5, §7): a deferred tool creates one
 * computation and records it in the dispatch index under {@link DispatchEntry.DispatchKind#TOOL}, a
 * redrive while it is pending does not dispatch the tool again, completing folds the result, a
 * redelivered completion is ignored (spec §4's at-least-once claim), an expired tool computation
 * folds an in-band failure, and the index entry is gone once the fold's own batch commits (spec
 * §5).
 *
 * <p>Case 7 restores the deferred-grant-arm property {@code GrantSurvivalTest} covered before Task
 * 3 deleted it: a {@code requireApproval} grant over a tool that itself defers, driven end to end —
 * the exact branch spec §11.3 gap 2 names as failing open before this task's {@link
 * ComputationDeferredToolCallPolicy#onDeferred} started overwriting the index entry
 * unconditionally.
 */
class DeferredToolOnContinuumTest {

  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  record NoInput() {}

  /** Always defers, on every invocation — never resolves inline. */
  private static final class DeferringTool implements Tool<NoInput> {
    private final String name;
    final AtomicInteger invocations = new AtomicInteger();

    DeferringTool(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return "always defers";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      invocations.incrementAndGet();
      return Awaited.deferred();
    }
  }

  /**
   * Always defers, declares itself durable, and never runs synchronously — the fixture for {@link
   * #aDeferredToolOnARealHarnessParksAndResumesThroughTheHarnessOwnCompletionsDoor()}, which drives
   * {@link org.jwcarman.nessy.agent.host.Nessy#harness} end to end rather than this file's
   * hand-wired {@link #harness} field.
   */
  private static final class EndToEndDeferringTool implements Tool<NoInput> {
    @Override
    public String name() {
      return "central_op";
    }

    @Override
    public String description() {
      return "defers durably; answered out of band";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      return Awaited.deferred();
    }
  }

  private static final class NoopBacklog implements Backlog<String> {
    @Override
    public void add(String observation) {}

    @Override
    public Optional<String> poll() {
      return Optional.empty();
    }
  }

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final InMemorySubstrate substrate = new InMemorySubstrate();
  private final TestClock clock = new TestClock(Instant.parse("2026-08-24T00:00:00Z"));
  private final Continuum continuum =
      new DefaultContinuum(new InMemoryContinuumRepository(), clock);
  private final ContinuumClient<ToolResult, Routing> toolClient =
      continuum.client(
          "tool/test",
          ToolResult.class,
          Routing.class,
          cfg ->
              cfg.resultCodec(TestToolClients.toolResultCodec(mapper))
                  .continuationCodec(Routing.codec(mapper))
                  .deadline(Duration.ofHours(1)));
  private final ContinuumClient<Decision, Routing> approvalClient =
      continuum.client(
          "approval/test",
          Decision.class,
          Routing.class,
          cfg ->
              cfg.resultCodec(DecisionCodec.codec(mapper))
                  .continuationCodec(Routing.codec(mapper))
                  .deadline(Duration.ofDays(7)));
  private final DispatchIndex index = new DispatchIndex(substrate, mapper, "dispatch/test");
  private final DeferringTool tool = new DeferringTool("restart");
  private final DeferringTool gatedTool = new DeferringTool("restart_gated");
  private final PumpedExecutor pump = new PumpedExecutor();
  private final RecordingTurnObserver turn = new RecordingTurnObserver();
  private final RecordingMemory memory = new RecordingMemory();
  private final SubstrateAgentStateStore store =
      new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
  private final List<ApprovalRequest> notifications = new ArrayList<>();
  private final ComputationDeferredToolCallPolicy deferredPolicy =
      new ComputationDeferredToolCallPolicy(index, toolClient);
  private final ComputationApprover approver =
      new ComputationApprover(approvalClient, index, store, notifications::add);
  private final RegistryToolCallExecutor executor =
      new RegistryToolCallExecutor(
          ToolRegistry.of(
              ToolGrant.grant(tool, UsagePolicy.allow()),
              ToolGrant.grant(gatedTool, UsagePolicy.requireApproval())),
          AgentType.of("test"),
          AgentId.of("test-scope"),
          turn,
          pump,
          deferredPolicy,
          approver,
          mapper);
  private final Harness<String> harness =
      TestAgents.<String>harness(
          memory,
          store,
          new NoopBacklog(),
          text -> List.of(),
          sink -> {},
          executor,
          AgentObserver.noop(),
          false,
          StalenessPolicy.after(Duration.ZERO));
  private final Agent<String> agent = harness.bind(AgentId.of("test-scope"));

  /**
   * A {@link PumpedExecutor}, never pumped in this file: every {@code completions.complete}/{@code
   * approvals.approve} call below is immediately followed by an explicit, synchronous {@link
   * #drainTools()}/{@link #drainApprovals()} — {@link DeliveryWorker#nudge()}'s own submitted drain
   * would just be redundant, so it is left queued and unpumped rather than raced against it.
   */
  private final PumpedExecutor nudgePump = new PumpedExecutor();

  private final DeliveryWorker<String> worker =
      new DeliveryWorker<>(
          substrate,
          mapper,
          harness,
          (type, id) -> agent,
          nudgePump,
          approvalClient,
          index,
          toolClient);
  private final CompletionDesk completions = new CompletionDesk(toolClient, worker::nudge);
  private final ApprovalDesk approvals = new ApprovalDesk(approvalClient, worker::nudge);

  private ToolCall deferringCall(String callId) {
    return new ToolCall(callId, "restart", JsonNodeFactory.instance.objectNode());
  }

  private ToolCall gatedDeferringCall(String callId) {
    return new ToolCall(callId, "restart_gated", JsonNodeFactory.instance.objectNode());
  }

  private CallAddress addressOf(ToolCall call) {
    return new CallAddress("test", "test-scope", "r1", call.id());
  }

  private Routing routingFor(ToolCall call) {
    return new Routing("test", "test-scope", "r1", call);
  }

  private void drainTools() {
    worker.drainTools(BatchSize.of(10));
  }

  private void drainApprovals() {
    worker.drainApprovals(BatchSize.of(10));
  }

  /**
   * Seeds the scope's state to {@code AwaitingTools} pending {@code call}, then dispatches once.
   */
  private void driveOnceWithPending(ToolCall call) {
    store.save(
        new State(
            new Phase.AwaitingTools(
                Message.assistant(List.of(new ToolUseBlock(call))),
                Set.of(call.id()),
                List.of(),
                ModelResponseId.of("r1")),
            0));
    agent.drive();
    pump.pumpUntilQuiet();
  }

  private void redrive() {
    agent.drive();
    pump.pumpUntilQuiet();
  }

  private List<ToolResultBlock> foldedResults() {
    return memory.recall().messages().stream()
        .flatMap(m -> m.content().stream())
        .filter(ToolResultBlock.class::isInstance)
        .map(ToolResultBlock.class::cast)
        .toList();
  }

  @Test
  void aDeferredToolCreatesOneComputationAndRecordsIt() {
    var call = deferringCall("c1");
    driveOnceWithPending(call);

    assertThat(tool.invocations).hasValue(1); // dispatched exactly once
    assertThat(index.find(addressOf(call)))
        .hasValueSatisfying(
            entry -> assertThat(entry.kind()).isEqualTo(DispatchEntry.DispatchKind.TOOL));
  }

  @Test
  void aRedriveWhileTheToolIsPendingDoesNotDispatchAgain() {
    var call = deferringCall("c1");
    driveOnceWithPending(call);
    redrive();

    assertThat(tool.invocations).hasValue(1);
  }

  @Test
  void completingTheComputationFoldsTheResult() {
    var call = deferringCall("c1");
    driveOnceWithPending(call);
    ComputationId id = ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());

    completions.complete(id, ToolResult.ok("done"));
    drainTools();

    assertThat(foldedResults()).singleElement().satisfies(r -> assertThat(r.isError()).isFalse());
  }

  /**
   * The spec §4 claim under test: fold a result, deliver the same outcome again, assert the
   * transition was ignored and no second remembrance was written. A genuine second delivery through
   * the client is not reachable here — once {@link #drainTools()} acknowledges Continuum's
   * delivery, Continuum has nothing left queued to redeliver — so this drives {@link
   * DeliveryWorker#foldOutcome} a second time with the same outcome directly, the fallback the
   * brief names explicitly.
   *
   * <p>{@code foldedResults()).hasSize(1)} alone proves nothing here: {@link
   * org.jwcarman.nessy.agent.support.RecordingMemory}'s delegate ({@link
   * org.jwcarman.nessy.agent.memory.VerbatimMemory}) is {@code putIfAbsent} on {@link
   * org.jwcarman.nessy.spi.Remembrance#key()}, and {@link ToolFoldRemembrance} keys every fold on
   * the deterministic {@code address.indexKey()} — so even a broken reducer that re-accepted the
   * second {@code ToolFinished} would still collapse to one transcript entry via memory-layer
   * key-idempotence, not reducer dedup. This asserts two signals a broken dedup could not produce
   * either: the scope's own state version did not advance (the reducer's own {@code ignore()} arm
   * commits no state write), and {@code remember} was invoked exactly once, not twice (a
   * re-accepted event would call {@code remember} again with the same key, which {@code
   * RecordingMemory#facts()} would show even though {@code recall()} still deduplicates it).
   */
  @Test
  void aRedeliveredCompletionIsIgnored() {
    var call = deferringCall("c1");
    driveOnceWithPending(call);
    ComputationId id = ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());
    completions.complete(id, ToolResult.ok("done"));
    drainTools();

    assertThat(foldedResults()).isNotEmpty();
    long versionAfterFirstFold = store.load().version();
    int rememberCallsAfterFirstFold = memory.facts().size();

    drainTools(); // a second pass: nothing left to deliver
    worker.foldOutcome(routingFor(call), new TypedOutcome.Success<>(ToolResult.ok("done")));

    assertThat(store.load().version()).isEqualTo(versionAfterFirstFold);
    assertThat(memory.facts()).hasSize(rememberCallsAfterFirstFold);
    assertThat(foldedResults()).hasSize(1);
  }

  @Test
  void anExpiredToolComputationFoldsAFailure() {
    var call = deferringCall("c1");
    driveOnceWithPending(call);

    clock.advance(Duration.ofHours(2)); // past the tool deadline
    // The behaviour under test is real, but its production trigger is not wired yet: as of Task 4,
    // failExpiredComputations still has no caller in src/main; Task 5 wires it (mirrors Task 3's
    // own expiry test note for the approval kind).
    toolClient.failExpiredComputations(BatchSize.of(10));
    drainTools();

    assertThat(foldedResults()).singleElement().satisfies(r -> assertThat(r.isError()).isTrue());
  }

  @Test
  void theIndexEntryIsGoneAfterTheFold() {
    var call = deferringCall("c1");
    driveOnceWithPending(call);
    ComputationId id = ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());

    completions.complete(id, ToolResult.ok("done"));
    drainTools();

    assertThat(index.find(addressOf(call))).isEmpty();
  }

  /**
   * Restores the property {@code DeliveryWorkerTest}'s {@code
   * aConflictingStateWriteForcesARetryAndLeavesNoOrphanJournalEntries} pinned over the now-deleted
   * Substrate-outbox path (continuum-adoption spec §6): a competitor's identical-phase state write
   * landing between {@link DeliveryWorker#foldToolOutcome}'s own read and its commit batch is a
   * genuine CAS conflict the fold must retry past, not a semantic change to swallow. Only the STATE
   * write races here; Continuum's own repository (the tool computation itself) never touches {@code
   * substrate} at all, so a second {@link DeliveryWorker}, wired over a raced view of the SAME
   * substrate but sharing every other collaborator with the class's own {@link #worker}, is enough
   * to force the retry without re-wiring Continuum. The retry itself legitimately remembers twice
   * (once per read-then-batch attempt) — {@code recall()}'s own key-idempotence, not a call count,
   * is what proves no duplicate: exactly one folded result reaches the transcript despite the
   * forced retry, and the scope actually advances rather than getting stuck retrying forever.
   */
  @Test
  void aConflictingStateWriteDuringTheFoldForcesARetryThatStillConverges() {
    var call = deferringCall("c1");
    driveOnceWithPending(call);
    ComputationId id = ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());
    completions.complete(id, ToolResult.ok("restarted"));

    byte[] currentStatePayload = substrate.read("state", "test-scope").orElseThrow().payload();
    var raced = new RaceOnceOnBatchSubstrate(substrate, "state", "test-scope", currentStatePayload);
    var racedWorker =
        new DeliveryWorker<String>(
            raced,
            mapper,
            harness,
            (type, i) -> agent,
            nudgePump,
            approvalClient,
            index,
            toolClient);

    racedWorker.drainTools(BatchSize.of(10));

    assertThat(foldedResults()).singleElement().satisfies(r -> assertThat(r.isError()).isFalse());
    assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingModel.class);
  }

  /**
   * Restores the property {@code DeliveryWorkerTest}'s {@code
   * aThrowingRememberAbortsBeforeTheCommitBatchThenHealsOnTheNextNudge} pinned over the now-deleted
   * Substrate-outbox path (continuum-adoption spec §6): remembrance spec §1 law 1 — a throwing
   * {@code remember} must abort the fold attempt BEFORE the commit batch ever runs, leaving the
   * scope's state untouched. Continuum's own {@code deliverResults} (unlike the retired outbox
   * scan's own drive-by-hand redrive) already catches a throwing consumer and releases the delivery
   * for a later retry after its own backoff — so healing here means advancing the test clock past
   * the tool kind's backoff and draining again, not a second {@code nudge()}.
   */
  @Test
  void aThrowingRememberAbortsTheFoldThenHealsOnceTheBackoffElapses() {
    var call = deferringCall("c2");
    driveOnceWithPending(call);
    ComputationId id = ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());
    completions.complete(id, ToolResult.ok("restarted"));

    var recording = new RecordingMemory();
    var throwingMemory = new ThrowingThenDelegatingMemory(recording, 1); // throws once, then heals
    var throwingHarness =
        TestAgents.<String>harness(
            throwingMemory,
            store,
            new NoopBacklog(),
            text -> List.of(),
            sink -> {},
            executor,
            AgentObserver.noop(),
            false,
            StalenessPolicy.never());
    var throwingAgent = throwingHarness.bind(AgentId.of("test-scope"));
    var throwingWorker =
        new DeliveryWorker<String>(
            substrate,
            mapper,
            throwingHarness,
            (type, i) -> throwingAgent,
            nudgePump,
            approvalClient,
            index,
            toolClient);
    long versionBefore = store.load().version();

    throwingWorker.drainTools(BatchSize.of(10)); // the throwing arm — Continuum releases for retry

    assertThat(store.load().version())
        .isEqualTo(versionBefore); // untouched — aborted before commit

    clock.advance(Duration.ofSeconds(6)); // past the tool kind's own backoff (5s)
    throwingWorker.drainTools(BatchSize.of(10)); // memory has healed — the redrive folds cleanly

    assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingModel.class);
    List<Remembrance.ToolExchange> exchangesForTheCall =
        recording.facts().stream()
            .filter(Remembrance.ToolExchange.class::isInstance)
            .map(Remembrance.ToolExchange.class::cast)
            .filter(exchange -> exchange.call().id().equals(call.id()))
            .toList();
    assertThat(exchangesForTheCall).hasSize(1); // exactly one, despite the failed first attempt
  }

  @Test
  void aGrantedToolThatDefersTransfersAndItsEventualAnswerFolds() {
    var call = gatedDeferringCall("c1");
    driveOnceWithPending(call);
    ComputationId approval =
        ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());

    approvals.approve(approval);
    drainApprovals();

    // the grant ran the tool, the tool deferred, and the entry now names the TOOL kind
    assertThat(index.find(addressOf(call)))
        .hasValueSatisfying(
            entry -> assertThat(entry.kind()).isEqualTo(DispatchEntry.DispatchKind.TOOL));
    assertThat(foldedResults()).isEmpty();

    ComputationId execution =
        ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());
    completions.complete(execution, ToolResult.ok("eventually"));
    drainTools();

    assertThat(foldedResults()).singleElement().satisfies(r -> assertThat(r.isError()).isFalse());
  }

  /**
   * Case 8 (task-4 fix round, Fix 3): the one-runtime replacement for the deleted {@code
   * DurableParkDemo} — a real {@link Nessy#harness} rather than this file's own hand-wired {@link
   * #harness} field. Nothing else in the suite drives a durable tool completion all the way through
   * {@link Harness#completions()} and asserts the TURN resumed (cases 3 and 7 above only assert a
   * {@link ToolResultBlock} reached memory, never that the follow-up model call happened and the
   * phase returned to {@link Phase.Idle}); nothing else executes {@code HarnessConfig#finish()}'s
   * tool-kind wiring either, so this is also the one test that would notice if production's result
   * codec ({@code substrate.codecs().create(ToolResult.class)}) ever disagreed with the hand-rolled
   * {@link TestToolClients#toolResultCodec} every other test in this file uses.
   */
  @Test
  void aDeferredToolOnARealHarnessParksAndResumesThroughTheHarnessOwnCompletionsDoor()
      throws InterruptedException {
    var e2eSubstrate = new InMemorySubstrate();
    var e2eMapper = TestMappers.plainlyPinned();
    var call = new ToolCall("c1", "central_op", JsonNodeFactory.instance.objectNode());
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("all done."))));
    var pump = new PumpedExecutor();
    var e2eHarness =
        Nessy.harness(
            h ->
                h.type("e2e-tool")
                    .model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(ToolGrant.grant(new EndToEndDeferringTool(), UsagePolicy.allow()))
                    .substrate(e2eSubstrate)
                    .executor(pump));
    try {
      var scopeState =
          new SubstrateAgentStateStore(e2eSubstrate, "scope-1", Clock.systemUTC(), e2eMapper);
      e2eHarness.bind(AgentId.of("scope-1")).tell("please run the central op");
      pump.pumpUntilQuiet();

      assertThat(scopeState.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
      var responseId = ((Phase.AwaitingTools) scopeState.load().phase()).responseId();
      // The dispatch index is the only handle back to the Continuum-minted id (spec §5) — built
      // fresh here over the same substrate/kind HarnessConfig#finish() derived internally, exactly
      // as a genuinely separate out-of-band responder would have to.
      var e2eIndex =
          new DispatchIndex(e2eSubstrate, e2eMapper, Kinds.dispatchIndex(AgentType.of("e2e-tool")));
      var address = new CallAddress("e2e-tool", "scope-1", responseId.value(), "c1");
      var computation = ComputationId.of(e2eIndex.find(address).orElseThrow().computationId());

      // "every instance is garbage; any node may answer": complete purely through the harness's
      // own public door, exactly as a genuinely out-of-band responder would.
      e2eHarness.completions().complete(computation, ToolResult.ok("central op done"));
      // completions().complete nudges asynchronously now (continuum-adoption spec §7): the fold
      // runs on the harness's own ComputationScheduler thread, which dispatches the follow-up
      // model call onto `pump` from that same background thread — so this awaits the turn's own
      // resumption (Idle) rather than assuming a single pumpUntilQuiet() call already caught it.
      long deadline = System.currentTimeMillis() + 5000;
      while (!(scopeState.load().phase() instanceof Phase.Idle)
          && System.currentTimeMillis() < deadline) {
        pump.pumpUntilQuiet();
        Thread.sleep(20);
      }

      // the property DurableParkDemo pinned and cases 3/7 above do not: the turn actually RESUMED,
      // not merely that a ToolResultBlock landed in memory — the follow-up model call ran and the
      // scope reached Idle.
      assertThat(scopeState.load().phase()).isEqualTo(new Phase.Idle());
      var transcript = new SubstrateMemory(e2eSubstrate, "scope-1", e2eMapper).recall().messages();
      assertThat(transcript)
          .anySatisfy(
              m ->
                  assertThat(m.content())
                      .contains(new ToolResultBlock("c1", "central op done", false)));
      assertThat(provider.requests()).hasSize(2); // the parked call, then the resumed follow-up
    } finally {
      e2eHarness.shutdown();
    }
  }
}
