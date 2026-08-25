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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/** The §3 matrix, row by row: one test per stated transition, plus every stale row. */
class AwaitingToolsPhaseTest {

  private static final ToolCall CALL_A =
      new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("c2", "restart", JsonNodeFactory.instance.objectNode());
  private static final ToolCall STRANGER =
      new ToolCall("c9", "stranger", JsonNodeFactory.instance.objectNode());
  private static final Message TURN =
      Message.assistant(
          List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, null)));
  private static final ModelResponseId RESPONSE_ID = ModelResponseId.of("response-1");
  private static final ComputationId PARKED = ComputationId.of("parked-1");
  private static final ComputationId OTHER = ComputationId.of("parked-other");
  private static final ApprovalRequest REQUEST =
      ApprovalRequest.draft("ops", "prod-1", CALL_A, new ObjectMapper())
          .action("restart prod-1")
          .freeze();

  private static Phase.AwaitingTools awaiting(Map<String, CallStatus> calls) {
    return new Phase.AwaitingTools(TURN, calls, RESPONSE_ID);
  }

  private static Map<String, CallStatus> calls(CallStatus first, CallStatus second) {
    return Map.of("c1", first, "c2", second);
  }

  private static AgentEvent.ApprovalAnswered answered(
      ToolCall call, Optional<ComputationId> approval, Approval answer) {
    return new AgentEvent.ApprovalAnswered(call, approval, answer);
  }

  private static AgentEvent.ToolFinished returned(
      ToolCall call, Optional<ComputationId> tool, String content) {
    return new AgentEvent.ToolFinished(
        call, tool, new ToolOutcome.Returned(ToolResult.ok(content)));
  }

  @Test
  void pendingApprovedInProcessRunsTheTool() {
    var phase = awaiting(calls(new CallStatus.Pending(), new CallStatus.Pending()));

    var t = phase.handle(answered(CALL_A, Optional.empty(), Approval.approved()));

    assertThat(t.next())
        .isEqualTo(awaiting(calls(new CallStatus.Running(), new CallStatus.Pending())));
    assertThat(t.effects()).containsExactly(new Effect.RunTool(CALL_A));
    assertThat(t.commit()).isEmpty();
  }

  @Test
  void pendingDeniedInProcessFinishesWithAFailedResult() {
    var phase = awaiting(calls(new CallStatus.Pending(), new CallStatus.Pending()));

    var t = phase.handle(answered(CALL_A, Optional.empty(), Approval.denied("not today")));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new CallStatus.Finished(new ToolResultBlock("c1", "not today", true)),
                    new CallStatus.Pending())));
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void pendingDeferredBecomesAwaitingApproval() {
    var phase = awaiting(calls(new CallStatus.Pending(), new CallStatus.Pending()));

    var t = phase.handle(new AgentEvent.ApprovalDeferred(CALL_A, PARKED, REQUEST));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(new CallStatus.AwaitingApproval(PARKED, REQUEST), new CallStatus.Pending())));
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void pendingWithADeliveredAnswerIsIgnoredAsEarly() {
    var phase = awaiting(calls(new CallStatus.Pending(), new CallStatus.Pending()));

    var t = phase.handle(answered(CALL_A, Optional.of(PARKED), Approval.approved()));

    assertThat(t.isIgnored()).isTrue();
  }

  @Test
  void awaitingApprovalApprovedByItsIdRunsTheTool() {
    var phase =
        awaiting(calls(new CallStatus.AwaitingApproval(PARKED, REQUEST), new CallStatus.Pending()));

    var t = phase.handle(answered(CALL_A, Optional.of(PARKED), Approval.approved()));

    assertThat(t.next())
        .isEqualTo(awaiting(calls(new CallStatus.Running(), new CallStatus.Pending())));
    assertThat(t.effects()).containsExactly(new Effect.RunTool(CALL_A));
  }

  @Test
  void awaitingApprovalDeniedByItsIdFinishesWithAFailedResult() {
    var phase =
        awaiting(calls(new CallStatus.AwaitingApproval(PARKED, REQUEST), new CallStatus.Pending()));

    var t = phase.handle(answered(CALL_A, Optional.of(PARKED), Approval.denied("no")));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new CallStatus.Finished(new ToolResultBlock("c1", "no", true)),
                    new CallStatus.Pending())));
  }

  @Test
  void awaitingApprovalAnsweredUnderAnotherIdIsIgnoredAsStale() {
    var phase =
        awaiting(calls(new CallStatus.AwaitingApproval(PARKED, REQUEST), new CallStatus.Pending()));

    var t = phase.handle(answered(CALL_A, Optional.of(OTHER), Approval.approved()));

    assertThat(t.isIgnored()).isTrue();
  }

  @Test
  void runningFinishedInProcessFinishes() {
    var phase = awaiting(calls(new CallStatus.Running(), new CallStatus.Pending()));

    var t = phase.handle(returned(CALL_A, Optional.empty(), "42"));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new CallStatus.Finished(new ToolResultBlock("c1", "42", false)),
                    new CallStatus.Pending())));
  }

  @Test
  void runningDeferredBecomesAwaitingResult() {
    var phase = awaiting(calls(new CallStatus.Running(), new CallStatus.Pending()));

    var t = phase.handle(new AgentEvent.ToolDeferred(CALL_A, PARKED));

    assertThat(t.next())
        .isEqualTo(
            awaiting(calls(new CallStatus.AwaitingResult(PARKED), new CallStatus.Pending())));
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void runningWithADeliveredResultIsIgnoredAsEarly() {
    var phase = awaiting(calls(new CallStatus.Running(), new CallStatus.Pending()));

    var t = phase.handle(returned(CALL_A, Optional.of(PARKED), "42"));

    assertThat(t.isIgnored()).isTrue();
  }

  @Test
  void awaitingResultFinishedByItsIdFinishes() {
    var phase = awaiting(calls(new CallStatus.AwaitingResult(PARKED), new CallStatus.Pending()));

    var t = phase.handle(returned(CALL_A, Optional.of(PARKED), "42"));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new CallStatus.Finished(new ToolResultBlock("c1", "42", false)),
                    new CallStatus.Pending())));
  }

  @Test
  void awaitingResultFinishedUnderAnotherIdIsIgnoredAsStale() {
    var phase = awaiting(calls(new CallStatus.AwaitingResult(PARKED), new CallStatus.Pending()));

    var t = phase.handle(returned(CALL_A, Optional.of(OTHER), "42"));

    assertThat(t.isIgnored()).isTrue();
  }

  @Test
  void finishedIgnoresEverythingForThatCall() {
    var finished = new CallStatus.Finished(new ToolResultBlock("c1", "42", false));
    var phase = awaiting(calls(finished, new CallStatus.Pending()));

    assertThat(phase.handle(returned(CALL_A, Optional.empty(), "again")).isIgnored()).isTrue();
    assertThat(phase.handle(answered(CALL_A, Optional.empty(), Approval.approved())).isIgnored())
        .isTrue();
    assertThat(phase.handle(new AgentEvent.ToolDeferred(CALL_A, PARKED)).isIgnored()).isTrue();
    assertThat(phase.handle(new AgentEvent.ApprovalDeferred(CALL_A, PARKED, REQUEST)).isIgnored())
        .isTrue();
  }

  @Test
  void anUnknownCallIsIgnored() {
    var phase = awaiting(calls(new CallStatus.Pending(), new CallStatus.Pending()));

    assertThat(phase.handle(returned(STRANGER, Optional.empty(), "42")).isIgnored()).isTrue();
    assertThat(phase.handle(answered(STRANGER, Optional.empty(), Approval.approved())).isIgnored())
        .isTrue();
  }

  @Test
  void theLastCallFinishingCommitsTheTurnInTheAssistantsOrderAndCallsTheModel() {
    var phase =
        awaiting(
            calls(
                new CallStatus.Finished(new ToolResultBlock("c1", "42", false)),
                new CallStatus.Running()));

    var t = phase.handle(returned(CALL_B, Optional.empty(), "ok"));

    assertThat(t.next()).isEqualTo(new Phase.AwaitingModel());
    assertThat(t.commit())
        .containsExactly(
            TURN,
            Message.toolResults(
                List.of(
                    new ToolResultBlock("c1", "42", false),
                    new ToolResultBlock("c2", "ok", false))));
    assertThat(t.effects()).containsExactly(new Effect.CallModel());
  }

  @Test
  void aDeniedCallsResultIsAnErrorBlockCarryingTheReason() {
    var phase =
        awaiting(
            calls(
                new CallStatus.Finished(new ToolResultBlock("c1", "42", false)),
                new CallStatus.AwaitingApproval(PARKED, REQUEST)));

    var t = phase.handle(answered(CALL_B, Optional.of(PARKED), Approval.denied("too risky")));

    assertThat(t.commit())
        .containsExactly(
            TURN,
            Message.toolResults(
                List.of(
                    new ToolResultBlock("c1", "42", false),
                    new ToolResultBlock("c2", "too risky", true))));
  }

  @Test
  void aFailedToolRendersInBandAsAnErrorResult() {
    var phase = awaiting(calls(new CallStatus.Running(), new CallStatus.Pending()));
    var failed =
        new AgentEvent.ToolFinished(
            CALL_A, Optional.empty(), new ToolOutcome.Failed(new ToolError("timed out")));

    var t = phase.handle(failed);

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new CallStatus.Finished(new ToolResultBlock("c1", "timed out", true)),
                    new CallStatus.Pending())));
  }

  @Test
  void outstandingEffectsReseekPendingAndRerunRunningAndLeaveTheParkedOnesAlone() {
    assertThat(
            awaiting(calls(new CallStatus.Pending(), new CallStatus.Running()))
                .outstandingEffects())
        .containsExactly(new Effect.SeekApproval(CALL_A), new Effect.RunTool(CALL_B));
    assertThat(
            awaiting(
                    calls(
                        new CallStatus.AwaitingApproval(PARKED, REQUEST),
                        new CallStatus.AwaitingResult(PARKED)))
                .outstandingEffects())
        .isEmpty();
  }

  @Test
  void aStrayModelCompletionIsIgnored() {
    var phase = awaiting(calls(new CallStatus.Pending(), new CallStatus.Pending()));
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Failed("late duplicate"));

    assertThat(phase.handle(event).isIgnored()).isTrue();
  }

  @Test
  void anObservationReachingThisPhaseIsAProgrammingError() {
    var phase = awaiting(calls(new CallStatus.Pending(), new CallStatus.Pending()));
    var event = new AgentEvent.Observed(List.of(new TextBlock("hi")));

    assertThatThrownBy(() -> phase.handle(event)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void awaitingNothingIsNotAPhase() {
    Map<String, CallStatus> empty = Map.of();

    assertThatThrownBy(() -> new Phase.AwaitingTools(TURN, empty, RESPONSE_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCallIdAbsentFromTheHeldBackTurnIsRejected() {
    Map<String, CallStatus> ghost = Map.of("ghost", new CallStatus.Pending());

    assertThatThrownBy(() -> new Phase.AwaitingTools(TURN, ghost, RESPONSE_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
