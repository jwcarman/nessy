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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.ComputationId;
import org.jwcarman.continuum.api.ContinuationId;
import org.jwcarman.continuum.api.TypedDelivery;
import org.jwcarman.continuum.api.TypedOutcome;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.slf4j.LoggerFactory;

/**
 * Guard 2 (continuum-adoption spec §11.1): {@link DeliveryWorker#foldOutcome} folding against a
 * scope with no stored {@code state} document is the exact instant a tool result vanishes under a
 * durability mismatch — {@code Idle.handle(ToolFinished)} ignores the fold silently, which is why
 * it must be logged rather than left indistinguishable from an ordinary duplicate-delivery ignore.
 * Drives {@link DeliveryWorker#foldOutcome} directly, package-visibly, over a freshly-constructed
 * {@link InMemorySubstrate} that has never had a {@code state} document written for the scope —
 * mirrors {@code DeferredToolOnContinuumTest}'s own direct-construction fixture shape. The appender
 * is wired directly onto {@link DeliveryWorker}'s own class logger, the same technique {@code
 * DurabilityMismatchWarningTest} uses.
 */
class DeliveryWorkerSilentLossWarningTest {

  private static final Memory NOOP_MEMORY =
      new Memory() {
        @Override
        public void remember(Remembrance remembrance) {
          // fixture only: an ignored transition never remembers anything
        }

        @Override
        public Context recall() {
          return Context.empty();
        }
      };

  private static final Backlog<String> NOOP_BACKLOG =
      new Backlog<>() {
        @Override
        public void add(String observation) {
          // fixture only: never exercised by this test
        }

        @Override
        public Optional<String> poll() {
          return Optional.empty();
        }
      };

  private static final ObservationRenderer<String> RENDERER = text -> List.of();
  private static final ModelCallExecutor MODEL = sink -> {};
  private static final ToolCallExecutor TOOLS = new NoToolsExecutor();

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
    HarnessTeardown.shutdownAllTracked();
  }

  @Test
  void a_delivery_folding_against_a_scope_with_no_stored_state_logs_a_warning() {
    ObjectMapper mapper = TestMappers.plainlyPinned();
    AgentType type = AgentType.of("test");
    // never written to: DeliveryWorker's own readState finds no "state" document for "test-scope"
    var substrate = new InMemorySubstrate();
    AgentStateStore harnessStore =
        new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);

    Harness<String> harness =
        TestAgents.<String>harness(
            type,
            NOOP_MEMORY,
            harnessStore,
            NOOP_BACKLOG,
            RENDERER,
            MODEL,
            TOOLS,
            AgentObserver.noop(),
            false,
            StalenessPolicy.after(Duration.ZERO));
    HarnessTeardown.track(harness);
    Agent<String> agent = harness.bind(AgentId.of("test-scope"));

    ContinuumClient<Approval, ApprovalRouting> approvalClient =
        TestApprovalClients.client(Kinds.approval(type), mapper);
    ContinuumClient<ToolResult, Routing> toolClient =
        TestToolClients.client(Kinds.tool(type), mapper);
    DeliveryWorker<String> worker =
        new DeliveryWorker<>(
            substrate,
            mapper,
            harness,
            (t, id) -> agent,
            Runnable::run,
            approvalClient,
            toolClient);

    ToolCall call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    Routing routing = new Routing(type.name(), "test-scope", "r1", call);

    worker.foldOutcome(
        new TypedDelivery<>(
            new ComputationId(UUID.randomUUID()),
            ContinuationId.random(),
            routing,
            new TypedOutcome.Success<>(ToolResult.ok("done")),
            Instant.EPOCH,
            Instant.EPOCH,
            1));

    // Two warnings now, not one: the missing-state warning this test is about, and — since an
    // Idle scope ignores the fold — the mismatched-delivery drop warning (James's 2026-08-25
    // ruling). The size assertion below is on the silent-loss warning alone, so it keeps saying
    // exactly what it always said: that instant is logged once, and distinguishably.
    List<ILoggingEvent> warnings =
        appender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .filter(event -> event.getFormattedMessage().contains("no stored state"))
            .toList();
    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().getFormattedMessage())
        .contains("test-scope")
        .contains("no stored state");
  }
}
