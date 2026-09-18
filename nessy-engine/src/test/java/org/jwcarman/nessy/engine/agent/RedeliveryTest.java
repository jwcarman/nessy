package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.engine.backlog.Backlog;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.Failure;

/**
 * An outcome arriving for work the agent no longer owes.
 *
 * <p>Redelivery is designed in, not guarded against: an outcome is folded in before its effect row
 * is retired, so a crash between the two leaves a row that comes due again and is performed twice.
 * That is the safe order -- the alternative loses outcomes -- and it is safe only because the fold
 * recognises the second delivery and does nothing with it.
 *
 * <p>Doing something with it is the one corruption that cannot be undone. A second tool result puts
 * two answers in the story for a call that has one, which no provider accepts; a second inference
 * outcome closes a turn that still owes results, leaving calls with nothing answering them. Neither
 * is a degraded agent, it is a wedged one, so every shape that can arrive in the wrong state has a
 * case here rather than a comment.
 */
class RedeliveryTest {

  private static final CallId CALL = new CallId("c1");

  /** A model call is outstanding; nothing here owes a tool anything. */
  private static AgentState.Inferring<String> inferring() {
    return new AgentState.Inferring<>(new Seq(2), new TurnId(1), Backlog.empty());
  }

  /** Calls are outstanding and running; the inference that asked for them is finished. */
  private static AgentState.AwaitingActions<String> running() {
    return awaiting(Outstanding.Phase.RUNNING);
  }

  private static AgentState.AwaitingActions<String> awaiting(Outstanding.Phase phase) {
    Map<CallId, Outstanding> calls = new LinkedHashMap<>();
    calls.put(CALL, new Outstanding(new ToolName("lookup"), phase));
    return new AgentState.AwaitingActions<>(
        new Seq(2), new TurnId(1), new Seq(2), Backlog.empty(), calls);
  }

  /** Every shape a finished tool effect can arrive in. */
  static Stream<Arguments> toolOutcomes() {
    return Stream.of(
        Arguments.of("succeeded", new EffectOutcome.ToolSucceeded(CALL, result())),
        Arguments.of("failed", new EffectOutcome.ToolFailed(CALL, "it broke")),
        Arguments.of("denied", new EffectOutcome.ToolDenied(CALL, "not allowed")),
        Arguments.of("approved", new EffectOutcome.ToolApproved(CALL)));
  }

  /** Every shape a finished inference effect can arrive in. */
  static Stream<Arguments> inferenceOutcomes() {
    return Stream.of(
        Arguments.of("answered", new EffectOutcome.InferenceAnswered(answer())),
        Arguments.of(
            "failed",
            new EffectOutcome.InferenceFailed(new Failure.Transient("the socket closed"))),
        Arguments.of("refused", new EffectOutcome.InferenceRefused("content")),
        Arguments.of("requested actions", new EffectOutcome.InferenceRequestedActions(request())));
  }

  private static List<Block.ToolResultContent> result() {
    return HistoryEntry.ToolSucceeded.text("a result");
  }

  private static List<Block.AnswerContent> answer() {
    return HistoryEntry.InferenceAnswered.text("an answer");
  }

  private static List<Block.ActionRequestContent> request() {
    return List.of(new Block.ToolCall("c9", "lookup", "{}"));
  }

  /**
   * A tool result while a model call is outstanding belongs to a turn that already moved on.
   * Recording it would put a second result in the story for a call that already has one.
   */
  @ParameterizedTest(name = "a redelivered tool {0} is ignored while inferring")
  @MethodSource("toolOutcomes")
  void aToolOutcomeArrivingWhileInferringIsIgnored(String shape, EffectOutcome outcome) {
    assertThat(inferring().outcome(outcome)).isInstanceOf(Decision.Ignore.class);
  }

  /**
   * The inference that asked for these calls is finished and its row is gone, so anything of its
   * shape now would close a turn that still owes results.
   */
  @ParameterizedTest(name = "a redelivered inference {0} is ignored while calls are running")
  @MethodSource("inferenceOutcomes")
  void anInferenceOutcomeArrivingWhileActionsAreOwedIsIgnored(String shape, EffectOutcome outcome) {
    assertThat(running().outcome(outcome)).isInstanceOf(Decision.Ignore.class);
  }

  /**
   * Approving removes nothing from the outstanding set, so presence alone cannot tell a first
   * approval from a second -- the phase is what does. Dispatching again would run the tool twice,
   * which for anything touching the world outside the agent is the mistake with no way back.
   */
  @Test
  void anApprovalRedeliveredForACallAlreadyRunningDispatchesNothing() {
    assertThat(running().outcome(new EffectOutcome.ToolApproved(CALL)))
        .isInstanceOf(Decision.Ignore.class);
  }

  /** Nobody asked for this call, so there is nothing to grant permission for. */
  @Test
  void anApprovalForACallNobodyAskedAboutDispatchesNothing() {
    assertThat(
            awaiting(Outstanding.Phase.AWAITING_APPROVAL)
                .outcome(new EffectOutcome.ToolApproved(new CallId("never-asked"))))
        .isInstanceOf(Decision.Ignore.class);
  }
}
