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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
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
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.TypedOutcome;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
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
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
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
              cfg.resultCodec(toolResultCodec(mapper))
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
  private final VerbatimMemory memory = new VerbatimMemory();
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
          StalenessPolicy.never());
  private final Agent<String> agent = harness.bind(AgentId.of("test-scope"));
  private final DeliveryWorker<String> worker =
      new DeliveryWorker<>(
          substrate, mapper, harness, (type, id) -> agent, approvalClient, index, toolClient);
  private final CompletionDesk completions = new CompletionDesk(toolClient, worker::nudge);
  private final ApprovalDesk approvals = new ApprovalDesk(approvalClient, worker::nudge);

  /**
   * {@link ToolResult} carries no Jackson polymorphism of its own (a plain record), so the pinned
   * mapper binds it directly — no hand-rolled discriminated shape needed the way {@link
   * DecisionCodec} exists for the approval kind's {@code Decision}.
   */
  private static Codec<ToolResult> toolResultCodec(ObjectMapper mapper) {
    return new Codec<>() {
      @Override
      public byte[] encode(ToolResult value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable tool result", e);
        }
      }

      @Override
      public ToolResult decode(byte[] bytes) {
        try {
          return mapper.readValue(new String(bytes, StandardCharsets.UTF_8), ToolResult.class);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable tool result", e);
        }
      }
    };
  }

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
    ((DefaultAgent<String>) agent).redispatch();
    pump.pumpUntilQuiet();
  }

  private void redrive() {
    ((DefaultAgent<String>) agent).redispatch();
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
   * brief names explicitly. Assert emptiness before the single-element assertion (S5841 sibling
   * discipline).
   */
  @Test
  void aRedeliveredCompletionIsIgnored() {
    var call = deferringCall("c1");
    driveOnceWithPending(call);
    ComputationId id = ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());
    completions.complete(id, ToolResult.ok("done"));
    drainTools();

    drainTools(); // a second pass: nothing left to deliver
    worker.foldOutcome(routingFor(call), new TypedOutcome.Success<>(ToolResult.ok("done")));

    assertThat(foldedResults()).isNotEmpty();
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
}
