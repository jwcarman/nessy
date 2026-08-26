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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
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
import org.jwcarman.nessy.api.CompletionPolicy;
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
 * The proof the whole reform exists for (tool-context-defer spec §9): a tool takes the id out of
 * {@code context.defer()} and hands it to an external system that answers IMMEDIATELY — completed
 * AND drained on this very thread, before the tool has returned. The answer still lands, because
 * {@code defer()} folded {@code AwaitingResult} and committed before it gave the id up. "Ordered by
 * construction," not "usually fast enough": there is no window for the drop rule's WARN to fire in,
 * and the log proves it did not. The tool-side mirror of the approval lifecycle's early-answer
 * test.
 *
 * <p>The drain is deliberately SYNCHRONOUS, inside the tool body — {@code
 * CompletionDesk#complete}'s own nudge is asynchronous, and a background drain that happens to run
 * after the tool returns would make this test pass against the retired fold-afterwards ordering
 * too, proving nothing. Draining here forces the answer to meet whatever status the phase holds at
 * the instant the tool still has not returned.
 */
class ToolHandsOutItsIdBeforeReturningTest {

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

  /**
   * The external system, standing in three lines: complete the computation the instant the address
   * is known, drain it home on this same thread, and only then return {@code deferred()}.
   */
  private final class AnsweredBeforeItReturnsTool implements Tool<NoInput> {

    private ComputationId handedOut;

    @Override
    public String name() {
      return "central_op";
    }

    @Override
    public String description() {
      return "hands its id to an external system that answers at once";
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
      handedOut = context.defer();
      completions.complete(handedOut, ToolResult.ok("answered at once"));
      worker.drainTools(BatchSize.of(10));
      return Awaited.deferred();
    }
  }

  private final AnsweredBeforeItReturnsTool tool = new AnsweredBeforeItReturnsTool();

  private final RegistryToolCallExecutor executor =
      new RegistryToolCallExecutor(
          ToolRegistry.of(ToolGrant.grant(tool, Approvers.allow())),
          AgentType.of("test"),
          AgentId.of("test-scope"),
          new RecordingTurnObserver(),
          pump,
          approvalClient,
          toolClient,
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

  private final DeliveryWorker<String> worker =
      new DeliveryWorker<>(
          substrate,
          mapper,
          harness,
          (type, id) -> agent,
          new PumpedExecutor(),
          approvalClient,
          toolClient);
  private final CompletionDesk completions = new CompletionDesk(toolClient, worker::nudge);

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

  @Test
  void anAnswerThatArrivesBeforeTheToolReturnsStillReachesTheTurn() {
    store.save(
        new State(
            new Phase.AwaitingTools(
                Message.assistant(List.of(new ToolUseBlock(CALL))),
                Map.of(CALL.id(), new CallStatus.Pending()),
                ModelResponseId.of("r1")),
            0));

    agent.drive();
    pump.pumpUntilQuiet();

    assertThat(tool.handedOut).isNotNull();
    assertThat(foldedResults()).isNotEmpty();
    assertThat(foldedResults())
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.isError()).isFalse();
              assertThat(result.content()).isEqualTo("answered at once");
            });
    // nothing was dropped: the phase named the wait before the answer could arrive
    assertThat(warnings()).isEmpty();
  }
}
