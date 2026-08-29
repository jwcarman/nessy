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
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.TypedOutcome;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationCallback;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * §9a's second mandatory cell, end to end: <b>a callback that throws fails the call — it does not
 * re-ask.</b>
 *
 * <p>If the callback throws, the only thing we know is that it threw. We do NOT know whether it got
 * as far as telling the world before it blew up. Re-asking would assume it did not, which is an
 * assumption we are not entitled to make, and it would risk telling the world twice. So the effect
 * fails the computation it just created, folds a failure completion, and the call lands in the
 * terminal {@code Failed} carrying the exception's detail.
 *
 * <p>Under the 2026-08-26 ordering ruling the park has ALREADY folded by the time a callback can
 * throw, so the call is in {@code Awaiting…} and the failure rides the id that state recorded — the
 * only shape §3 lets it admit. It is folded in band by the effect rather than left to Continuum's
 * own delivery of the failed computation, and that matters on the approval side: the worker maps a
 * failed approval computation to a DENIAL, which would tell the model a human said no when nobody
 * decided anything.
 *
 * <p>Without all this the call would sit waiting forever: the failure would be dropped for want of
 * a state that admits it, the computation would dangle until its deadline, and the whole suite
 * would stay green throughout. That is what these tests exist to prevent, which is why they assert
 * the call is {@code Failed} and, explicitly, that it is not {@code Awaiting…}.
 */
class HandoffCallbackThrowsTest {

  private static final Duration TERM = Duration.ofDays(30);

  private static final ToolCall CALL =
      new ToolCall("c1", "central_op", JsonNodeFactory.instance.objectNode());

  /** A sibling that never finishes, so the turn stays open and the phase stays readable. */
  private static final ToolCall SIBLING =
      new ToolCall("c2", "other_op", JsonNodeFactory.instance.objectNode());

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final ContinuumClient<ToolResult, Routing> toolClient =
      TestToolClients.client("tool/test", mapper);
  private final ContinuumClient<Approval, ApprovalRouting> approvalClient =
      TestApprovalClients.client("approval/test", mapper);
  private final PumpedExecutor pump = new PumpedExecutor();

  private final ToolCallExecutor executor =
      new RegistryToolCallExecutor(
          ToolRegistry.of(new Tool<?>[0]),
          AgentType.of("test"),
          AgentId.of("test-scope"),
          new RecordingTurnObserver(),
          pump,
          approvalClient,
          toolClient,
          mapper,
          ObservationRegistry.NOOP,
          () -> null,
          Duration.ofDays(7),
          Duration.ofDays(1));

  /** The callback every test here hands the harness: it explodes, having told nobody anything. */
  private static ComputationCallback exploding(AtomicInteger runs) {
    return (id, deadline) -> {
      runs.incrementAndGet();
      throw new IllegalStateException("the pager is down");
    };
  }

  private static AgentPhase seeded(ToolCallPhase state) {
    return new AgentPhase.AwaitingTools(
        Message.assistant(List.of(new ToolUseBlock(CALL, null), new ToolUseBlock(SIBLING, null))),
        Map.of(CALL.id(), state, SIBLING.id(), new ToolCallPhase.SeekingApproval()),
        ModelResponseId.of("r1"));
  }

  /** Folds every event the handoff delivered through the real reducer, in arrival order. */
  private AgentPhase foldAll(AgentPhase seed, List<AgentEvent> delivered) {
    AgentPhase phase = seed;
    for (AgentEvent event : delivered) {
      AgentTransition transition = phase.handle(event);
      if (!transition.isDropped()) {
        phase = transition.next();
      }
    }
    return phase;
  }

  private static ToolCallPhase callIn(AgentPhase phase) {
    return ((AgentPhase.AwaitingTools) phase).calls().get(CALL.id());
  }

  private static ApprovalRequest request(ObjectMapper mapper) {
    return ApprovalRequest.draft("test", "test-scope", CALL, Map.of(), mapper)
        .action("do the thing")
        .freeze();
  }

  @Nested
  class On_the_approval_side {

    @Test
    void a_throwing_callback_fails_the_call_rather_than_parking_it() {
      var runs = new AtomicInteger();
      var delivered = new ArrayList<AgentEvent>();

      executor.deferApproval(
          CALL, request(mapper), exploding(runs), TERM, ModelResponseId.of("r1"), delivered::add);
      pump.pumpUntilQuiet();
      ToolCallPhase state =
          callIn(foldAll(seeded(new ToolCallPhase.DeferringApproval()), delivered));

      assertThat(runs).hasValue(1);
      assertThat(state).isNotInstanceOf(ToolCallPhase.AwaitingApproval.class);
      assertThat(state)
          .isInstanceOfSatisfying(
              ToolCallPhase.Failed.class,
              failed -> {
                assertThat(failed.block().isError()).isTrue();
                assertThat(failed.block().text()).contains("the pager is down");
              });
    }

    @Test
    void the_computation_it_created_is_failed_rather_than_left_to_expire() {
      var delivered = new ArrayList<AgentEvent>();
      executor.deferApproval(
          CALL,
          request(mapper),
          exploding(new AtomicInteger()),
          TERM,
          ModelResponseId.of("r1"),
          delivered::add);
      pump.pumpUntilQuiet();

      var outcomes = new ArrayList<TypedOutcome<Approval>>();
      approvalClient.deliverResults(BatchSize.of(10), d -> outcomes.add(d.outcome()));

      assertThat(outcomes).isNotEmpty();
      assertThat(outcomes).allMatch(TypedOutcome.Failure.class::isInstance);
    }
  }

  @Nested
  class On_the_tool_side {

    @Test
    void a_throwing_callback_fails_the_call_rather_than_parking_it() {
      var runs = new AtomicInteger();
      var delivered = new ArrayList<AgentEvent>();

      executor.deferToolCall(CALL, exploding(runs), TERM, ModelResponseId.of("r1"), delivered::add);
      pump.pumpUntilQuiet();
      ToolCallPhase state = callIn(foldAll(seeded(new ToolCallPhase.DeferringResult()), delivered));

      assertThat(runs).hasValue(1);
      assertThat(state).isNotInstanceOf(ToolCallPhase.AwaitingResult.class);
      assertThat(state)
          .isInstanceOfSatisfying(
              ToolCallPhase.Failed.class,
              failed -> {
                assertThat(failed.block().isError()).isTrue();
                assertThat(failed.block().text()).contains("the pager is down");
              });
    }

    @Test
    void the_computation_it_created_is_failed_rather_than_left_to_expire() {
      var delivered = new ArrayList<AgentEvent>();
      executor.deferToolCall(
          CALL, exploding(new AtomicInteger()), TERM, ModelResponseId.of("r1"), delivered::add);
      pump.pumpUntilQuiet();

      var outcomes = new ArrayList<TypedOutcome<ToolResult>>();
      toolClient.deliverResults(BatchSize.of(10), d -> outcomes.add(d.outcome()));

      assertThat(outcomes).isNotEmpty();
      assertThat(outcomes).allMatch(TypedOutcome.Failure.class::isInstance);
    }
  }
}
