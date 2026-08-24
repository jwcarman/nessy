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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.ThrowingOnWriteSubstrate;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.slf4j.LoggerFactory;

/**
 * {@link ComputationApprover#adjudicate} — chiefly the §11.5 guard: {@link DispatchIndex#record}
 * can throw after {@link ContinuumClient#create} has already minted a computation, at which point
 * the computation is orphaned unless the failure is at least logged loudly before it propagates
 * (continuum-adoption spec §11.5).
 */
class ComputationApproverTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
  private static final ModelResponseId RESPONSE_ID = ModelResponseId.of("response-1");

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final TestClock clock = new TestClock(Instant.parse("2026-08-24T00:00:00Z"));
  private final Continuum continuum =
      new DefaultContinuum(new InMemoryContinuumRepository(), clock);
  private final ContinuumClient<Decision, Routing> client =
      continuum.client(
          "approval/test",
          Decision.class,
          Routing.class,
          cfg ->
              cfg.resultCodec(DecisionCodec.codec(mapper))
                  .continuationCodec(Routing.codec(mapper))
                  .deadline(Duration.ofDays(7)));

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void wires_a_capturing_appender_onto_computation_approvers_own_logger() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(ComputationApprover.class);
    classicLogger.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.start();
    classicLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(ComputationApprover.class);
    classicLogger.detachAppender(appender);
    classicLogger.setLevel(null);
  }

  @Test
  void aDispatchIndexRecordFailureIsLoggedThenRethrown() {
    var substrate =
        new ThrowingOnWriteSubstrate(
            new InMemorySubstrate(),
            "dispatch/test",
            () -> new IllegalStateException("index write boom"));
    var index = new DispatchIndex(substrate, mapper, "dispatch/test");
    var store = new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
    store.save(
        new State(
            new Phase.AwaitingTools(
                Message.assistant(List.of(new ToolUseBlock(CALL, null))),
                Set.of("c1"),
                List.of(),
                RESPONSE_ID),
            0));
    var notifications = new ArrayList<ApprovalRequest>();
    var approver = new ComputationApprover(client, index, store, notifications::add);
    var request =
        new ApprovalRequest(
            ComputationId.of("placeholder"),
            CALL,
            "test",
            "test-scope",
            AuthzContext.of("test", CALL));

    assertThatThrownBy(() -> approver.adjudicate(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("index write boom");

    List<ILoggingEvent> errors =
        appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    assertThat(errors).hasSize(1);
    assertThat(errors.getFirst().getFormattedMessage())
        .contains("DispatchIndex.record")
        .contains("ComputationApprover.adjudicate");
  }
}
