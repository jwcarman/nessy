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
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ObservationCoalescer;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.engine.backlog.Backlog;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * The half of the fold that owes work to somebody other than a model.
 *
 * <p>Everything here turns on one invariant: <em>every call gets exactly one result.</em> Fewer and
 * the conversation cannot be sent to any provider again -- the agent is not degraded, it is wedged.
 * More and the provider rejects the duplicate. So the tests are mostly about the two ways to break
 * it: losing a result, and writing one twice.
 */
class AwaitingActionsTest {

  private static final Instant T0 = Instant.parse("2026-09-08T12:00:00Z");
  private static final ObservationCoalescer<String> KEEP_ALL = ObservationCoalescer.keepAll();

  private static Decision.Advance<String> advance(Decision<String> decision) {
    assertThat(decision).isInstanceOf(Decision.Advance.class);
    return (Decision.Advance<String>) decision;
  }

  private static Block.ToolCall call(String id) {
    return new Block.ToolCall(id, "lookup", "{}");
  }

  private static Map<CallId, Outstanding> phases(Outstanding.Phase phase, String... callIds) {
    Map<CallId, Outstanding> calls = new LinkedHashMap<>();
    for (String callId : callIds) {
      calls.put(new CallId(callId), new Outstanding(new ToolName("lookup"), phase));
    }
    return calls;
  }

  /** An agent mid-turn with two calls already approved and running. */
  private static AgentState.AwaitingActions<String> awaiting(Backlog<String> backlog) {
    return new AgentState.AwaitingActions<>(
        new Seq(2),
        new TurnId(1),
        new Seq(2),
        backlog,
        phases(Outstanding.Phase.RUNNING, "c1", "c2"));
  }

  /** The same two, still only asked about. */
  private static AgentState.AwaitingActions<String> asking() {
    return new AgentState.AwaitingActions<>(
        new Seq(2),
        new TurnId(1),
        new Seq(2),
        Backlog.empty(),
        phases(Outstanding.Phase.AWAITING_APPROVAL, "c1", "c2"));
  }

  private static EffectOutcome succeeded(String callId) {
    return new EffectOutcome.ToolSucceeded(
        new CallId(callId), HistoryEntry.ToolSucceeded.text("result of " + callId));
  }

  // ---- getting here -------------------------------------------------------------------

  /**
   * The request is written and the effects are emitted in the same decision, so the obligations
   * become durable in the very transaction that records taking them on. There is no instant at
   * which the story says calls were made and no rows exist to make them.
   */
  @Test
  void askingForToolsRecordsTheRequestAndOwesOneEffectPerCall() {
    Decision.Advance<String> decision =
        advance(
            new AgentState.Inferring<String>(new Seq(1), new TurnId(1), Backlog.empty())
                .outcome(
                    new EffectOutcome.InferenceRequestedActions(
                        List.of(new Block.Commentary("looking"), call("c1"), call("c2")))));

    assertThat(decision.next())
        .isEqualTo(
            new AgentState.AwaitingActions<>(
                new Seq(2),
                new TurnId(1),
                new Seq(2),
                Backlog.empty(),
                phases(Outstanding.Phase.AWAITING_APPROVAL, "c1", "c2")));
    assertThat(decision.recorded())
        .containsExactly(
            new HistoryEntry.InferenceRequestedActions(
                new Seq(2),
                new TurnId(1),
                List.of(new Block.Commentary("looking"), call("c1"), call("c2"))));
    assertThat(decision.effects())
        .as("nothing is dispatched to a tool: every call is asked about first")
        .containsExactly(
            new AgentEffect.Approve(new Seq(2), new CallId("c1"), new ToolName("lookup")),
            new AgentEffect.Approve(new Seq(2), new CallId("c2"), new ToolName("lookup")));
    assertThat(decision.opensTurn()).as("the turn it belongs to is the one already open").isFalse();
  }

  /** An agent awaiting nothing would sit there forever, so the state cannot be built. */
  @Test
  void awaitingNothingIsNotAState() {
    Seq seq = new Seq(2);
    TurnId turn = new TurnId(1);
    Backlog<String> backlog = Backlog.empty();
    Map<CallId, Outstanding> nothing = Map.of();
    assertThatThrownBy(() -> new AgentState.AwaitingActions<>(seq, turn, seq, backlog, nothing))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- approving ------------------------------------------------------------------------

  /**
   * The grant and the call effect are written in one transaction, so there is no instant at which a
   * tool is dispatched with nothing in the story saying it was allowed. That is what makes "every
   * call that ran was approved" a property of the rows rather than a promise of the code.
   */
  @Test
  void anApprovalIsWrittenDownAndThenTheCallIsDispatched() {
    Decision.Advance<String> decision =
        advance(asking().outcome(new EffectOutcome.ToolApproved(new CallId("c1"))));

    assertThat(decision.recorded())
        .containsExactly(
            new HistoryEntry.ToolApproved(
                new Seq(3), new TurnId(1), new CallId("c1"), Optional.empty()));
    assertThat(decision.effects())
        .containsExactly(
            new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("lookup")));
    AgentState.AwaitingActions<String> next = (AgentState.AwaitingActions<String>) decision.next();
    assertThat(next.countOf(Outstanding.Phase.RUNNING)).isEqualTo(1);
    assertThat(next.countOf(Outstanding.Phase.AWAITING_APPROVAL)).isEqualTo(1);
  }

  /**
   * The reason the phase exists. Outcomes are delivered at least once, and presence alone cannot
   * tell a first approval from a second -- approving removes nothing. Without the phase this
   * dispatches the tool twice, which for anything touching the world is unrecoverable.
   */
  @Test
  void aRedeliveredApprovalDoesNotDispatchTheCallTwice() {
    AgentState<String> running =
        advance(asking().outcome(new EffectOutcome.ToolApproved(new CallId("c1")))).next();

    assertThat(running.outcome(new EffectOutcome.ToolApproved(new CallId("c1"))))
        .isInstanceOf(Decision.Ignore.class);
  }

  /** The join to whoever actually decided, carried from the verdict onto the entry. */
  @Test
  void anApproversReferenceReachesTheStory() {
    Decision.Advance<String> decision =
        advance(
            asking()
                .outcome(
                    new EffectOutcome.ToolApproved(new CallId("c1"), Optional.of("change-1187"))));

    assertThat(decision.recorded())
        .containsExactly(
            new HistoryEntry.ToolApproved(
                new Seq(3), new TurnId(1), new CallId("c1"), Optional.of("change-1187")));
  }

  /** A denial never reaches a tool, and carries its own join for the same reason. */
  @Test
  void aDenialDischargesTheCallWithoutEverDispatchingIt() {
    Decision.Advance<String> decision =
        advance(asking().outcome(new EffectOutcome.ToolDenied(new CallId("c1"), "out of hours")));

    assertThat(decision.recorded())
        .containsExactly(
            new HistoryEntry.ToolDenied(
                new Seq(3), new TurnId(1), new CallId("c1"), "out of hours"));
    assertThat(decision.effects()).isEmpty();
  }

  /**
   * Failing to ask discharges the call too. Refusing it here because the call had not reached
   * RUNNING would leave the agent waiting on an approval nobody will ever give again.
   */
  @Test
  void aFailureToAskDischargesFromTheApprovalPhase() {
    Decision.Advance<String> decision =
        advance(
            asking()
                .outcome(
                    new EffectOutcome.ToolFailed(new CallId("c1"), "could not be authorised")));

    assertThat(decision.recorded())
        .containsExactly(
            new HistoryEntry.ToolFailed(
                new Seq(3), new TurnId(1), new CallId("c1"), "could not be authorised"));
  }

  // ---- discharging --------------------------------------------------------------------

  @Test
  void oneResultLeavesTheRestOutstanding() {
    Decision.Advance<String> decision = advance(awaiting(Backlog.empty()).outcome(succeeded("c1")));

    assertThat(decision.next())
        .isEqualTo(
            new AgentState.AwaitingActions<>(
                new Seq(3),
                new TurnId(1),
                new Seq(2),
                Backlog.empty(),
                phases(Outstanding.Phase.RUNNING, "c2")));
    assertThat(decision.recorded())
        .containsExactly(
            new HistoryEntry.ToolSucceeded(
                new Seq(3),
                new TurnId(1),
                new CallId("c1"),
                HistoryEntry.ToolSucceeded.text("result of c1")));
    assertThat(decision.effects()).as("nothing new is owed until every call is in").isEmpty();
  }

  /**
   * A round ending is not a turn ending. The model asked for the work in order to answer, so once
   * it has the work it is asked again -- in the same turn, which stays open throughout.
   */
  @Test
  void theLastResultAsksTheModelAgainInTheSameTurn() {
    Decision.Advance<String> decision =
        advance(
            new AgentState.AwaitingActions<String>(
                    new Seq(3),
                    new TurnId(1),
                    new Seq(2),
                    Backlog.empty(),
                    phases(Outstanding.Phase.RUNNING, "c2"))
                .outcome(succeeded("c2")));

    assertThat(decision.next())
        .isEqualTo(new AgentState.Inferring<>(new Seq(4), new TurnId(1), Backlog.empty()));
    assertThat(decision.effects()).containsExactly(new AgentEffect.Infer());
  }

  /** A failure and a denial discharge a call as firmly as a success does. */
  @Test
  void aFailureAndADenialAlsoDischarge() {
    assertThat(
            advance(
                    new AgentState.AwaitingActions<String>(
                            new Seq(3),
                            new TurnId(1),
                            new Seq(2),
                            Backlog.empty(),
                            phases(Outstanding.Phase.RUNNING, "c2"))
                        .outcome(new EffectOutcome.ToolFailed(new CallId("c2"), "no network")))
                .recorded())
        .containsExactly(
            new HistoryEntry.ToolFailed(new Seq(4), new TurnId(1), new CallId("c2"), "no network"));

    assertThat(
            advance(
                    new AgentState.AwaitingActions<String>(
                            new Seq(3),
                            new TurnId(1),
                            new Seq(2),
                            Backlog.empty(),
                            phases(Outstanding.Phase.RUNNING, "c2"))
                        .outcome(new EffectOutcome.ToolDenied(new CallId("c2"), "not allowed")))
                .recorded())
        .containsExactly(
            new HistoryEntry.ToolDenied(
                new Seq(4), new TurnId(1), new CallId("c2"), "not allowed"));
  }

  /**
   * At-least-once delivery means a result can arrive twice. Writing the second would put two
   * results against one call, which every provider rejects -- so the set membership, not the
   * arrival, is what decides whether anything happens.
   */
  @Test
  void aRedeliveredResultIsIgnored() {
    assertThat(awaiting(Backlog.empty()).outcome(succeeded("nobody-asked")))
        .isInstanceOf(Decision.Ignore.class);
  }

  /**
   * The inference that got us here is finished and its row is gone. Recording a redelivery of it
   * would close a turn that still owes results, leaving calls with nothing answering them.
   */
  @Test
  void aRedeliveredInferenceOutcomeIsIgnored() {
    assertThat(
            awaiting(Backlog.empty())
                .outcome(
                    new EffectOutcome.InferenceAnswered(
                        HistoryEntry.InferenceAnswered.text("too late"))))
        .isInstanceOf(Decision.Ignore.class);
  }

  /** And the mirror: a tool result reaching an agent back on the model is equally stale. */
  @Test
  void aStaleToolResultReachingInferringIsIgnored() {
    assertThat(
            new AgentState.Inferring<String>(new Seq(4), new TurnId(1), Backlog.empty())
                .outcome(succeeded("c1")))
        .isInstanceOf(Decision.Ignore.class);
  }

  // ---- arrivals and endings -----------------------------------------------------------

  @Test
  void anObservationArrivingMidCallWaits() {
    Decision.Advance<String> decision =
        advance(awaiting(Backlog.empty()).observe("meanwhile", T0, KEEP_ALL));

    assertThat(decision.next()).isInstanceOf(AgentState.AwaitingActions.class);
    assertThat(((AgentState.AwaitingActions<String>) decision.next()).backlog().size())
        .isEqualTo(1);
    assertThat(decision.recorded()).isEmpty();
  }

  /**
   * Terminating mid-call seals the intake and leaves the calls owed. The seal has to survive the
   * trip back through inferring, or whoever sent the next observation would undo the termination.
   */
  @Test
  void terminatingMidCallSealsAndTheSealSurvivesTheRoundEnding() {
    Decision.Advance<String> sealed = advance(awaiting(Backlog.empty()).terminate());
    AgentState.AwaitingActions<String> stopped = (AgentState.AwaitingActions<String>) sealed.next();

    assertThat(stopped.observe("ignored", T0, KEEP_ALL))
        .as("a sealed backlog accepts nothing")
        .isInstanceOf(Decision.Ignore.class);

    Decision.Advance<String> first = advance(stopped.outcome(succeeded("c1")));
    Decision.Advance<String> last =
        advance(((AgentState.AwaitingActions<String>) first.next()).outcome(succeeded("c2")));

    assertThat(((AgentState.Inferring<String>) last.next()).backlog())
        .as("still sealed on the way back to the model")
        .isInstanceOf(Backlog.Sealed.class);
  }

  /** Sealing an already sealed backlog changed nothing, and nothing is what gets written. */
  @Test
  void terminatingTwiceMidCallWritesNothingTheSecondTime() {
    AgentState<String> stopped = advance(awaiting(Backlog.empty()).terminate()).next();
    assertThat(stopped.terminate()).isInstanceOf(Decision.Ignore.class);
  }
}
