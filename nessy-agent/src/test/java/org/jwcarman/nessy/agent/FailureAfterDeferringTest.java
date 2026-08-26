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
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
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
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.slf4j.LoggerFactory;

/**
 * A dispatch that fails AFTER {@code defer()} succeeded (tool-context-defer spec §1.2, §3, and the
 * 2026-08-25 fix ruling). By then the phase says {@code AwaitingResult(id)}, and the reducer admits
 * a {@code ToolFinished} against that status only when it carries the very id the phase names — so
 * an id-less failure would be IGNORED and the call would hang until the orphan computation expired.
 * The failure therefore rides the deferral's id, the reducer folds {@code Finished} at once, and
 * the orphan's eventual answer meets a call that is already finished and is dropped with the WARN
 * under the existing mismatch rule.
 *
 * <p>Everything here runs through the REAL reducer — a {@link DefaultAgent} over an in-memory store
 * — because a bare recording sink would happily accept an event the phase would have thrown away,
 * which is exactly the bug this file exists to catch.
 */
class FailureAfterDeferringTest {

  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  record NoInput() {}

  private static final ToolCall CALL =
      new ToolCall("c1", "central_op", JsonNodeFactory.instance.objectNode());

  private static final class NoopBacklog implements Backlog<String> {
    @Override
    public void add(String observation) {}

    @Override
    public Optional<String> poll() {
      return Optional.empty();
    }
  }

  /** Defers honestly, then does whatever {@code body} says — the two ways to fail afterwards. */
  private static final class DefersThenTool implements Tool<NoInput> {

    private final BiFunction<NoInput, ToolContext, Awaited<ToolResult>> body;
    private volatile ComputationId handedOut;

    DefersThenTool(BiFunction<NoInput, ToolContext, Awaited<ToolResult>> body) {
      this.body = body;
    }

    @Override
    public String name() {
      return "central_op";
    }

    @Override
    public String description() {
      return "defers, then misbehaves";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      handedOut = context.defer();
      return body.apply(input, context);
    }
  }

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final InMemorySubstrate substrate = new InMemorySubstrate();
  private final SubstrateAgentStateStore store =
      new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
  private final VerbatimMemory memory = new VerbatimMemory();
  private final ContinuumClient<ToolResult, Routing> toolClient =
      TestToolClients.client("tool/test", mapper);
  private final ContinuumClient<Approval, ApprovalRouting> approvalClient =
      TestApprovalClients.client("approval/test", mapper);
  private final PumpedExecutor pump = new PumpedExecutor();

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void wiresACapturingAppenderOntoDeliveryWorkersOwnLogger() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(DeliveryWorker.class);
    classicLogger.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.start();
    classicLogger.addAppender(appender);
  }

  @AfterEach
  void detachesTheAppender() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(DeliveryWorker.class);
    classicLogger.detachAppender(appender);
    classicLogger.setLevel(null);
  }

  private List<ILoggingEvent> warnings() {
    return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }

  private List<ToolResultBlock> foldedResults() {
    return memory.recall().messages().stream()
        .flatMap(m -> m.content().stream())
        .filter(ToolResultBlock.class::isInstance)
        .map(ToolResultBlock.class::cast)
        .toList();
  }

  /** Seeds the scope pending {@code CALL}, dispatches once, and hands back the tool that ran. */
  private DefersThenTool driveOnceWith(BiFunction<NoInput, ToolContext, Awaited<ToolResult>> body) {
    var tool = new DefersThenTool(body);
    var executor =
        new RegistryToolCallExecutor(
            ToolRegistry.of(ToolGrant.grant(tool, Approvers.allow())),
            AgentType.of("test"),
            AgentId.of("test-scope"),
            new RecordingTurnObserver(),
            pump,
            approvalClient,
            toolClient,
            mapper,
            ObservationRegistry.NOOP,
            () -> null);
    Harness<String> harness =
        TestAgents.<String>harness(
            memory,
            store,
            new NoopBacklog(),
            text -> List.of(),
            sink -> {},
            executor,
            HarnessObserver.noop(),
            false,
            StalenessPolicy.after(Duration.ZERO));
    Agent<String> agent = harness.bind(AgentId.of("test-scope"));
    worker =
        new DeliveryWorker<>(
            substrate,
            mapper,
            harness,
            (type, id) -> agent,
            new PumpedExecutor(),
            approvalClient,
            toolClient);
    store.save(
        new State(
            new Phase.AwaitingTools(
                Message.assistant(List.of(new ToolUseBlock(CALL))),
                Map.of(CALL.id(), new CallStatus.Pending()),
                ModelResponseId.of("r1")),
            0));
    agent.drive();
    pump.pumpUntilQuiet();
    return tool;
  }

  private DeliveryWorker<String> worker;

  private ToolResultBlock theOneFoldedResult() {
    assertThat(foldedResults()).isNotEmpty();
    assertThat(foldedResults()).hasSize(1);
    return foldedResults().getFirst();
  }

  @Test
  void aReadyAnswerAfterDeferringFinishesTheCallInBandInsteadOfHanging() {
    driveOnceWith((input, context) -> Awaited.ready(ToolResult.ok("too late")));

    ToolResultBlock result = theOneFoldedResult();
    assertThat(result.isError()).isTrue();
    // the literal, not the constant: it is package-private to agent.tool, and the wording is the
    // contract the spec names (§1.2)
    assertThat(result.content()).isEqualTo("tool answered after deferring");
    // the call is finished, so the whole turn committed and the scope moved on
    assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingModel.class);
  }

  @Test
  void aThrowAfterDeferringFinishesTheCallInBandInsteadOfHanging() {
    driveOnceWith(
        (input, context) -> {
          throw new IllegalStateException("the ticket system rejected it");
        });

    ToolResultBlock result = theOneFoldedResult();
    assertThat(result.isError()).isTrue();
    assertThat(result.content()).isEqualTo("the ticket system rejected it");
    assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingModel.class);
  }

  /**
   * The other half of the ruling: finishing the call in-band leaves a computation nobody is waiting
   * on, so the executor ends it there and then rather than letting it sit a day for the reaper.
   * Note what this test does NOT do — it completes nothing and advances no clock. The delivery is
   * already waiting, it meets a call the reducer has finished, and the existing mismatch rule drops
   * it with a WARN rather than folding a second result over the first.
   */
  @Test
  void theOrphanedComputationIsEndedAtOnceAndItsDeliveryIsDropped() {
    DefersThenTool tool =
        driveOnceWith((input, context) -> Awaited.ready(ToolResult.ok("too late")));
    assertThat(warnings()).isEmpty();
    List<ToolResultBlock> afterTheFailure = foldedResults();

    int delivered = worker.drainTools(BatchSize.of(10));
    pump.pumpUntilQuiet();

    assertThat(delivered).isEqualTo(1); // terminal already — no expiry needed
    assertThat(foldedResults()).isEqualTo(afterTheFailure);
    assertThat(warnings()).hasSize(1);
    assertThat(warnings().getFirst().getFormattedMessage())
        .contains("test-scope")
        .contains("c1")
        .contains(tool.handedOut.value());
  }
}
