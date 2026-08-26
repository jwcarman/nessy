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
      ApprovalRequest.draft("ops", "prod-1", CALL_A, Map.of(), new ObjectMapper())
          .action("restart prod-1")
          .freeze();

  private static AgentPhase.AwaitingTools awaiting(Map<String, ToolCallPhase> calls) {
    return new AgentPhase.AwaitingTools(TURN, calls, RESPONSE_ID);
  }

  private static Map<String, ToolCallPhase> calls(ToolCallPhase first, ToolCallPhase second) {
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
    var phase =
        awaiting(calls(new ToolCallPhase.SeekingApproval(), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(answered(CALL_A, Optional.empty(), Approval.approved()));

    assertThat(t.next())
        .isEqualTo(
            awaiting(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval())));
    assertThat(t.effects()).containsExactly(new Effect.RunTool(CALL_A));
    assertThat(t.commit()).isEmpty();
  }

  @Test
  void seekingApprovalDeniedInProcessResultsInDenied() {
    var phase =
        awaiting(calls(new ToolCallPhase.SeekingApproval(), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(answered(CALL_A, Optional.empty(), Approval.denied("not today")));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new ToolCallPhase.Denied(new ToolResultBlock("c1", "not today", true)),
                    new ToolCallPhase.SeekingApproval())));
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void pendingDeferredBecomesAwaitingApproval() {
    var phase =
        awaiting(calls(new ToolCallPhase.SeekingApproval(), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(new AgentEvent.ApprovalDeferred(CALL_A, PARKED, REQUEST));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new ToolCallPhase.AwaitingApproval(PARKED, REQUEST),
                    new ToolCallPhase.SeekingApproval())));
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void pendingWithADeliveredAnswerIsIgnoredAsEarly() {
    var phase =
        awaiting(calls(new ToolCallPhase.SeekingApproval(), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(answered(CALL_A, Optional.of(PARKED), Approval.approved()));

    assertThat(t.isDropped()).isTrue();
  }

  @Test
  void awaitingApprovalApprovedByItsIdRunsTheTool() {
    var phase =
        awaiting(
            calls(
                new ToolCallPhase.AwaitingApproval(PARKED, REQUEST),
                new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(answered(CALL_A, Optional.of(PARKED), Approval.approved()));

    assertThat(t.next())
        .isEqualTo(
            awaiting(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval())));
    assertThat(t.effects()).containsExactly(new Effect.RunTool(CALL_A));
  }

  @Test
  void awaitingApprovalDeniedByItsIdFinishesWithAFailedResult() {
    var phase =
        awaiting(
            calls(
                new ToolCallPhase.AwaitingApproval(PARKED, REQUEST),
                new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(answered(CALL_A, Optional.of(PARKED), Approval.denied("no")));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new ToolCallPhase.Denied(new ToolResultBlock("c1", "no", true)),
                    new ToolCallPhase.SeekingApproval())));
  }

  @Test
  void awaitingApprovalAnsweredUnderAnotherIdIsIgnoredAsStale() {
    var phase =
        awaiting(
            calls(
                new ToolCallPhase.AwaitingApproval(PARKED, REQUEST),
                new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(answered(CALL_A, Optional.of(OTHER), Approval.approved()));

    assertThat(t.isDropped()).isTrue();
  }

  @Test
  void runningToolFinishedInProcessResultsInCompleted() {
    var phase =
        awaiting(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(returned(CALL_A, Optional.empty(), "42"));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new ToolCallPhase.Completed(new ToolResultBlock("c1", "42", false)),
                    new ToolCallPhase.SeekingApproval())));
  }

  /**
   * A duplicate delivery of an answer already folded (spec §3): {@code Running} means the tool is
   * already going, so a second {@code Approved} must not emit a second {@link Effect.RunTool} — the
   * assertion is on the effects, not merely on {@code isDropped()}, so an implementation that
   * re-ran the tool would fail here.
   */
  @Test
  void runningIgnoresAnApprovalAnswerRatherThanRunningTheToolTwice() {
    var phase =
        awaiting(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval()));

    var inProcess = phase.handle(answered(CALL_A, Optional.empty(), Approval.approved()));
    var delivered = phase.handle(answered(CALL_A, Optional.of(PARKED), Approval.approved()));

    assertThat(inProcess.isDropped()).isTrue();
    assertThat(inProcess.effects()).isEmpty();
    assertThat(inProcess.commit()).isEmpty();
    assertThat(delivered.isDropped()).isTrue();
    assertThat(delivered.effects()).isEmpty();
    assertThat(delivered.commit()).isEmpty();
    assertThat(phase.calls())
        .isEqualTo(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval()));
  }

  /**
   * The tool has already deferred and Continuum holds its result; a late answer for the same call
   * must not put it back on the {@code Running} path with a fresh {@link Effect.RunTool}.
   */
  @Test
  void awaitingResultIgnoresAnApprovalAnswerRatherThanRunningTheToolTwice() {
    var phase =
        awaiting(
            calls(new ToolCallPhase.AwaitingResult(PARKED), new ToolCallPhase.SeekingApproval()));

    var byItsOwnId = phase.handle(answered(CALL_A, Optional.of(PARKED), Approval.approved()));
    var inProcess = phase.handle(answered(CALL_A, Optional.empty(), Approval.approved()));

    assertThat(byItsOwnId.isDropped()).isTrue();
    assertThat(byItsOwnId.effects()).isEmpty();
    assertThat(byItsOwnId.commit()).isEmpty();
    assertThat(inProcess.isDropped()).isTrue();
    assertThat(inProcess.effects()).isEmpty();
    assertThat(inProcess.commit()).isEmpty();
    assertThat(phase.calls())
        .isEqualTo(
            calls(new ToolCallPhase.AwaitingResult(PARKED), new ToolCallPhase.SeekingApproval()));
  }

  @Test
  void runningDeferredBecomesAwaitingResult() {
    var phase =
        awaiting(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(new AgentEvent.ToolDeferred(CALL_A, PARKED));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new ToolCallPhase.AwaitingResult(PARKED),
                    new ToolCallPhase.SeekingApproval())));
    assertThat(t.effects()).isEmpty();
  }

  /**
   * A {@code Running} call names no computation, so a delivered id is by definition one the scope
   * knows nothing of — ignored, exactly like every other mismatch (spec §3). There is no timing gap
   * this could be rescuing: the executor mints the computation on the {@code Awaited.Deferred} arm,
   * in the statement right after the tool body returns, and the very next statement on that same
   * thread folds {@code ToolDeferred}; with a one-day default deadline, an expiry could only land
   * here if the reaper and the deliver pump beat a single thread hop. On the crash path the
   * re-fired {@code RunTool} mints a second computation, and the orphan's expiry meets {@code
   * AwaitingResult(id2)} — a mismatch, dropped there. Admitting it would let a stale orphan finish
   * a live call.
   */
  @Test
  void runningIgnoresAToolFinishedBeforeItsDeferralFolded() {
    var phase =
        awaiting(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(returned(CALL_A, Optional.of(PARKED), "42"));

    assertThat(t.isDropped()).isTrue();
    assertThat(t.effects()).isEmpty();
    assertThat(t.commit()).isEmpty();
    assertThat(phase.calls())
        .isEqualTo(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval()));
  }

  @Test
  void awaitingResultFinishedByItsIdFinishes() {
    var phase =
        awaiting(
            calls(new ToolCallPhase.AwaitingResult(PARKED), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(returned(CALL_A, Optional.of(PARKED), "42"));

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new ToolCallPhase.Completed(new ToolResultBlock("c1", "42", false)),
                    new ToolCallPhase.SeekingApproval())));
  }

  @Test
  void awaitingResultFinishedUnderAnotherIdIsIgnoredAsStale() {
    var phase =
        awaiting(
            calls(new ToolCallPhase.AwaitingResult(PARKED), new ToolCallPhase.SeekingApproval()));

    var t = phase.handle(returned(CALL_A, Optional.of(OTHER), "42"));

    assertThat(t.isDropped()).isTrue();
  }

  @Test
  void completedIgnoresEverythingForThatCall() {
    var completed = new ToolCallPhase.Completed(new ToolResultBlock("c1", "42", false));
    var phase = awaiting(calls(completed, new ToolCallPhase.SeekingApproval()));

    assertThat(phase.handle(returned(CALL_A, Optional.empty(), "again")).isDropped()).isTrue();
    assertThat(phase.handle(answered(CALL_A, Optional.empty(), Approval.approved())).isDropped())
        .isTrue();
    assertThat(phase.handle(new AgentEvent.ToolDeferred(CALL_A, PARKED)).isDropped()).isTrue();
    assertThat(phase.handle(new AgentEvent.ApprovalDeferred(CALL_A, PARKED, REQUEST)).isDropped())
        .isTrue();
  }

  @Test
  void anUnknownCallIsIgnored() {
    var phase =
        awaiting(calls(new ToolCallPhase.SeekingApproval(), new ToolCallPhase.SeekingApproval()));

    assertThat(phase.handle(returned(STRANGER, Optional.empty(), "42")).isDropped()).isTrue();
    assertThat(phase.handle(answered(STRANGER, Optional.empty(), Approval.approved())).isDropped())
        .isTrue();
  }

  @Test
  void theLastCallFinishingCommitsTheTurnInTheAssistantsOrderAndCallsTheModel() {
    var phase =
        awaiting(
            calls(
                new ToolCallPhase.Completed(new ToolResultBlock("c1", "42", false)),
                new ToolCallPhase.RunningTool()));

    var t = phase.handle(returned(CALL_B, Optional.empty(), "ok"));

    assertThat(t.next()).isEqualTo(new AgentPhase.AwaitingModel());
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
                new ToolCallPhase.Completed(new ToolResultBlock("c1", "42", false)),
                new ToolCallPhase.AwaitingApproval(PARKED, REQUEST)));

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
    var phase =
        awaiting(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval()));
    var failed =
        new AgentEvent.ToolFinished(
            CALL_A, Optional.empty(), new ToolOutcome.Failed(new ToolError("timed out")));

    var t = phase.handle(failed);

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new ToolCallPhase.Failed(new ToolResultBlock("c1", "timed out", true)),
                    new ToolCallPhase.SeekingApproval())));
  }

  /**
   * A tool that returns normally but hands back an error result ({@code
   * ToolOutcome.Returned(ToolResult.error(...))}, as distinct from {@code ToolOutcome.Failed}) is a
   * NEW classification decision the terminal split introduced: before the split the {@code isError}
   * boolean just flowed into one {@code Finished} block, but now it decides which of two types the
   * call lands in. Asserting only the block's content, as {@link
   * #aFailedToolRendersInBandAsAnErrorResult} does for the other {@code Failed} path, would not
   * catch a misclassification into {@code Completed} — {@code result()} and {@code isInFlight}
   * treat the two identically, so the wrong type would be invisible without pinning the concrete
   * class here.
   */
  @Test
  void aToolThatReturnsAnErrorResultIsFailedNotCompleted() {
    var phase =
        awaiting(calls(new ToolCallPhase.RunningTool(), new ToolCallPhase.SeekingApproval()));
    var erroredReturn =
        new AgentEvent.ToolFinished(
            CALL_A, Optional.empty(), new ToolOutcome.Returned(ToolResult.error("bad input")));

    var t = phase.handle(erroredReturn);

    assertThat(t.next())
        .isEqualTo(
            awaiting(
                calls(
                    new ToolCallPhase.Failed(new ToolResultBlock("c1", "bad input", true)),
                    new ToolCallPhase.SeekingApproval())));
  }

  @Test
  void outstandingReseeksApprovalAndRerunsTheToolAndLeavesTheParkedOnesAlone() {
    assertThat(
            awaiting(calls(new ToolCallPhase.SeekingApproval(), new ToolCallPhase.RunningTool()))
                .outstanding())
        .containsExactly(new Effect.SeekApproval(CALL_A), new Effect.RunTool(CALL_B));
    assertThat(
            awaiting(
                    calls(
                        new ToolCallPhase.AwaitingApproval(PARKED, REQUEST),
                        new ToolCallPhase.AwaitingResult(PARKED)))
                .outstanding())
        .isEmpty();
  }

  @Test
  void aStrayModelCompletionIsIgnored() {
    var phase =
        awaiting(calls(new ToolCallPhase.SeekingApproval(), new ToolCallPhase.SeekingApproval()));
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Failed("late duplicate"));

    assertThat(phase.handle(event).isDropped()).isTrue();
  }

  @Test
  void anObservationReachingThisPhaseIsAProgrammingError() {
    var phase =
        awaiting(calls(new ToolCallPhase.SeekingApproval(), new ToolCallPhase.SeekingApproval()));
    var event = new AgentEvent.Observed(List.of(new TextBlock("hi")));

    assertThatThrownBy(() -> phase.handle(event)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void awaitingNothingIsNotAPhase() {
    Map<String, ToolCallPhase> empty = Map.of();

    assertThatThrownBy(() -> new AgentPhase.AwaitingTools(TURN, empty, RESPONSE_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCallIdAbsentFromTheHeldBackTurnIsRejected() {
    Map<String, ToolCallPhase> ghost = Map.of("ghost", new ToolCallPhase.SeekingApproval());

    assertThatThrownBy(() -> new AgentPhase.AwaitingTools(TURN, ghost, RESPONSE_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
