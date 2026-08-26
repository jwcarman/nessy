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
package org.jwcarman.nessy.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.slf4j.LoggerFactory;

/**
 * Fail-closed is right, but silent fail-closed is not: a thrown enricher or a thrown approver turns
 * into a denial whose reason carries only {@code getMessage()}, so the stack trace that says
 * <em>why</em> exists nowhere unless it is logged. The appender is wired onto {@link
 * RegistryToolCallExecutor}'s own class logger, the same technique {@code
 * DeliveryWorkerSilentLossWarningTest} uses.
 */
class AuthorizationFailureWarningTest {

  private static final ModelResponseId RESPONSE_ID = ModelResponseId.of("response-1");
  private static final ToolCall CALL =
      new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void wires_a_capturing_appender_onto_the_executors_own_logger() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(RegistryToolCallExecutor.class);
    classicLogger.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.start();
    classicLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(RegistryToolCallExecutor.class);
    classicLogger.detachAppender(appender);
    classicLogger.setLevel(null);
  }

  @Test
  void a_throwing_enricher_denies_the_call_and_logs_the_throwable() {
    Enricher boom =
        Enricher.named(
            "quota",
            draft -> {
              throw new IllegalStateException("quota service unreachable");
            });
    ActionContributor<RegistryToolCallExecutorTest.EchoInput, String> stringValueOf =
        String::valueOf;
    var registry =
        ToolRegistry.of(
            ToolGrant.grant(
                new RegistryToolCallExecutorTest.NeverRunTool(),
                stringValueOf,
                List.of(boom),
                Approvers.defer()));

    var delivered = seek(registry);

    assertThat(delivered).hasSize(1);
    var answered = (AgentEvent.ApprovalAnswered) delivered.getFirst();
    assertThat(((Approval.Denied) answered.answer()).reason()).startsWith("authorization failed:");
    assertThat(warnings()).isNotEmpty();
    assertThat(warnings().getFirst().getFormattedMessage()).contains("c1").contains("never_run");
    assertThat(warnings().getFirst().getThrowableProxy().getMessage())
        .contains("quota service unreachable");
  }

  @Test
  void a_throwing_approver_denies_the_call_and_logs_the_throwable() {
    Approver boom =
        context -> {
          throw new IllegalStateException("the desk is on fire");
        };
    ActionContributor<RegistryToolCallExecutorTest.EchoInput, String> stringValueOf =
        String::valueOf;
    var registry =
        ToolRegistry.of(
            ToolGrant.grant(
                new RegistryToolCallExecutorTest.NeverRunTool(), stringValueOf, List.of(), boom));

    var delivered = seek(registry);

    assertThat(delivered).hasSize(1);
    var answered = (AgentEvent.ApprovalAnswered) delivered.getFirst();
    assertThat(((Approval.Denied) answered.answer()).reason()).startsWith("approver failed:");
    assertThat(warnings()).isNotEmpty();
    assertThat(warnings().getFirst().getThrowableProxy().getMessage())
        .isEqualTo("the desk is on fire");
  }

  private List<ILoggingEvent> warnings() {
    return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }

  private static final ObjectMapper MAPPER = TestMappers.plainlyPinned();

  private List<AgentEvent> seek(ToolRegistry registry) {
    var pump = new PumpedExecutor();
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("cli"),
            AgentId.of("cli"),
            new RecordingTurnObserver(),
            pump,
            TestApprovalClients.client("approval/cli", MAPPER),
            TestToolClients.client("tool/cli", MAPPER),
            MAPPER,
            ObservationRegistry.NOOP,
            () -> null);
    var delivered = new ArrayList<AgentEvent>();
    executor.seekApproval(CALL, RESPONSE_ID, delivered::add);
    pump.pumpUntilQuiet();
    return List.copyOf(delivered);
  }
}
