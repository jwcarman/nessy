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
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * A call id can repeat across two requests of one turn (a vendor that mints none gets {@code
 * gemini-call-0} every time). An outcome produced for the first request's call, delivered a second
 * time, must not be taken for the second request's call.
 */
@DisplayName("A call id that repeats across two requests of one turn")
class AgentStateRepeatedCallIdTest {

  private static final PayloadRef MAIL = PayloadRef.of("mail");
  private static final PayloadRef FIRST_RESULT = PayloadRef.of("result-of-request-1");
  private static final PayloadRef SECOND_RESULT = PayloadRef.of("result-of-request-2");
  private static final CallId C = new CallId("c");
  private static final TurnId TURN = new TurnId(1);
  private static final ToolName TOOL = new ToolName("slow");

  private final AgentState idle = AgentState.idle(Seq.NONE);

  private static AgentState after(AgentState state, Decision decision) {
    return state.applyAll(decision.events());
  }

  private static AgentState askingForC(AgentState inferring) {
    return after(
        inferring,
        inferring.execute(
            new AgentCommand.CompleteInference(
                TURN,
                new AgentCommand.InferenceOutcome.RequestedActions(
                    MAIL,
                    List.of(new ActionRequest.ToolCall(C, TOOL, "tool")),
                    Usage.unreported()))));
  }

  private static AgentState approved(AgentState awaiting) {
    return after(
        awaiting,
        awaiting.execute(
            new AgentCommand.CompleteApproval(
                TURN, C, new AgentCommand.ApprovalOutcome.Approved(Optional.empty()))));
  }

  /** Request 1 asked for c, it was approved and succeeded; the model is inferring again. */
  private AgentState inferringAfterFirstRequest() {
    AgentState state = after(idle, idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)));
    state = approved(askingForC(state));
    return after(
        state,
        state.execute(
            new AgentCommand.CompleteToolCall(
                TURN, C, new AgentCommand.ToolOutcome.Succeeded(FIRST_RESULT, "FIRST"))));
  }

  private static String describe(Decision decision) {
    return decision.getClass().getSimpleName()
        + decision.events().stream()
            .map(e -> e.getClass().getSimpleName() + "@" + e.seq().value())
            .toList();
  }

  @Test
  @DisplayName("a duplicate success of request 1's call is not request 2's running call's result")
  void a_duplicate_success_does_not_settle_the_later_running_call() {
    AgentState running = approved(askingForC(inferringAfterFirstRequest()));
    AgentCommand.CompleteToolCall duplicate =
        new AgentCommand.CompleteToolCall(
            TURN, C, new AgentCommand.ToolOutcome.Succeeded(FIRST_RESULT, "FIRST"));

    Decision decision = running.execute(duplicate);

    System.out.println("TEST A1 duplicate success decided: " + describe(decision));
    assertThat(decision)
        .as(
            "request 1's outcome, re-delivered, must not become request 2's: %s",
            describe(decision))
        .isInstanceOf(Decision.Ignore.class);

    AgentState afterDuplicate = after(running, decision);
    Decision real =
        afterDuplicate.execute(
            new AgentCommand.CompleteToolCall(
                TURN, C, new AgentCommand.ToolOutcome.Succeeded(SECOND_RESULT, "SECOND")));
    assertThat(real.events())
        .as("request 2's real completion is recorded")
        .singleElement()
        .isInstanceOfSatisfying(
            AgentEvent.ToolSucceeded.class,
            recorded -> {
              assertThat(recorded.result()).isEqualTo(SECOND_RESULT);
              assertThat(recorded.rendered()).isEqualTo("SECOND");
            });
  }

  @Test
  @DisplayName("a duplicate failure of request 1's call does not fail request 2's awaiting call")
  void a_duplicate_failure_does_not_settle_the_later_call_awaiting_approval() {
    AgentState awaiting = askingForC(inferringAfterFirstRequest());
    AgentCommand.CompleteToolCall duplicate =
        new AgentCommand.CompleteToolCall(
            TURN, C, new AgentCommand.ToolOutcome.Failed("the first call's failure"));

    Decision decision = awaiting.execute(duplicate);

    System.out.println("TEST A2 duplicate failure decided: " + describe(decision));
    assertThat(decision)
        .as(
            "request 1's failure, re-delivered, must not fail request 2's call: %s",
            describe(decision))
        .isInstanceOf(Decision.Ignore.class);
  }
}
