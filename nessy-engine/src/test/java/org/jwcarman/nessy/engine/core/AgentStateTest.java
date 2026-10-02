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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.TurnDecision;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.agent.OutstandingAction;
import org.jwcarman.nessy.inference.Failure;

/**
 * The pure core, on its own: no provider, no database, no clock.
 *
 * <p>Every test here is a sequence of commands and the facts they came to, or a sequence of facts
 * and the state they build. If this file needs a mock, something has gone wrong with the design
 * rather than with the test.
 */
class AgentStateTest {

  private static final PayloadRef MAIL = PayloadRef.of("payload-1");
  private static final PayloadRef ANSWER = PayloadRef.of("payload-2");
  private static final PayloadRef RESULT = PayloadRef.of("payload-3");
  private static final CallId CALL = new CallId("call-1");

  /**
   * The only turn any state in this file is ever on: every one of them is built by starting a turn
   * on an agent at {@link Seq#NONE}, so its opening event lands at seq 1 and names turn 1.
   */
  private static final TurnId TURN = new TurnId(1);

  private static final ToolName TOOL = new ToolName("refund");

  private final AgentState idle = AgentState.idle(Seq.NONE);

  /** Runs a turn to the point where one call is outstanding and running. */
  private AgentState awaitingOneRunningCall() {
    AgentState state = idle;
    state = state.applyAll(state.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
    state =
        state.applyAll(
            state
                .execute(
                    new AgentCommand.CompleteInference(
                        TURN,
                        new AgentCommand.InferenceOutcome.RequestedActions(
                            MAIL,
                            List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool")),
                            Usage.unreported())))
                .events());
    return state.applyAll(
        state
            .execute(
                new AgentCommand.CompleteApproval(
                    TURN, CALL, new AgentCommand.ApprovalOutcome.Approved(Optional.empty())))
            .events());
  }

  @Nested
  @DisplayName("starting a turn")
  class Starting {

    @Test
    void an_idle_agent_records_the_turn_and_asks_the_model() {
      Decision decision = idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH));

      assertThat(decision.events()).singleElement().isInstanceOf(AgentEvent.TurnStarted.class);
      assertThat(decision.effects()).singleElement().isInstanceOf(AgentEffect.Infer.class);
    }

    @Test
    @DisplayName("the turn's id is the seq its opening event landed at")
    void the_turn_takes_its_id_from_its_position() {
      Decision decision = idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH));

      AgentEvent.TurnStarted started = (AgentEvent.TurnStarted) decision.events().getFirst();
      assertThat(started.turn()).isEqualTo(started.seq().opensTurn());
    }

    @Test
    @DisplayName("a busy agent records nothing: the harness holds the work and asks again")
    void a_busy_agent_ignores_it() {
      AgentState busy =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());

      assertThat(busy.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)))
          .isInstanceOf(Decision.Ignore.class);
    }
  }

  @Nested
  @DisplayName("replay")
  class Replay {

    @Test
    void applying_a_turn_start_leaves_the_agent_inferring() {
      AgentState state =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());

      assertThat(state).isInstanceOf(AgentState.Inferring.class);
    }

    @Test
    @DisplayName("an answer closes the turn and the agent is idle again")
    void an_answer_returns_to_idle() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());

      AgentState after =
          inferring.applyAll(
              inferring
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported())))
                  .events());

      assertThat(after).isInstanceOf(AgentState.Idle.class);
    }

    /** A turn with one tool call approved and running: the state a discharge lands on. */
    private AgentState awaitingOneCall() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      AgentState awaiting =
          inferring.applyAll(
              inferring
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.RequestedActions(
                              MAIL,
                              List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool")),
                              Usage.unreported())))
                  .events());
      return awaiting.applyAll(
          awaiting
              .execute(
                  new AgentCommand.CompleteApproval(
                      TURN, CALL, new AgentCommand.ApprovalOutcome.Approved(Optional.empty())))
              .events());
    }

    @Test
    @DisplayName("a turn past its bound is ended rather than asked again")
    void a_bounded_turn_is_failed_instead_of_continuing() {
      AgentState awaiting = awaitingOneCall();
      TurnPolicy stopNow = (stats, now) -> new TurnDecision.FailTurn("that is enough");

      Decision decision =
          awaiting.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, CALL, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")),
              stopNow,
              Instant.EPOCH);

      assertThat(decision.effects()).as("the whole point: the model is not asked again").isEmpty();
      assertThat(decision.events())
          .extracting(event -> event.getClass().getSimpleName())
          .as("what happened, then the decision about it")
          .containsExactly("ToolSucceeded", "TurnFailed");
      assertThat(decision.events().getLast())
          .asInstanceOf(InstanceOfAssertFactories.type(AgentEvent.TurnFailed.class))
          .extracting(AgentEvent.TurnFailed::reason)
          .isEqualTo("that is enough");
      assertThat(awaiting.applyAll(decision.events()))
          .as("and what it decided can be replayed, which is how every later read gets there")
          .isInstanceOf(AgentState.Idle.class);
    }

    @Test
    @DisplayName("a turn the policy failed can be replayed, and leaves the agent idle")
    void a_failed_turn_replays() {
      AgentState awaiting = awaitingOneCall();
      TurnPolicy stopNow = (stats, now) -> new TurnDecision.FailTurn("that is enough");

      Decision decision =
          awaiting.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, CALL, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")),
              stopNow,
              Instant.EPOCH);

      // The decision is only half of it. Everything that reads an agent afterwards -- another
      // ask, a terminate, any reconstitute at all -- gets there by replaying these events, so a
      // decision the fold cannot apply leaves the agent unusable rather than merely ended.
      assertThat(awaiting.applyAll(decision.events()))
          .as("the turn is over, so the agent is between turns")
          .isInstanceOf(AgentState.Idle.class);
    }

    @Test
    @DisplayName("a turn told to answer is asked again, but offered nothing to call")
    void a_turn_told_to_answer_asks_without_tools() {
      AgentState awaiting = awaitingOneCall();
      TurnPolicy wrapUp = (stats, now) -> new TurnDecision.AnswerNow();

      Decision decision =
          awaiting.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, CALL, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")),
              wrapUp,
              Instant.EPOCH);

      assertThat(decision.effects())
          .singleElement()
          .asInstanceOf(InstanceOfAssertFactories.type(AgentEffect.Infer.class))
          .extracting(AgentEffect.Infer::answerOnly)
          .as("still a call, and still counted as one -- what changes is what it may reach for")
          .isEqualTo(true);
      assertThat(awaiting.applyAll(decision.events()))
          .as("the turn is still open, waiting on the answer it just asked for")
          .isInstanceOf(AgentState.Inferring.class);
    }

    @Test
    @DisplayName("the turn's tally is rebuilt by replay, calls and cost alike")
    void the_tally_is_rebuilt_by_replay() {
      Instant opened = Instant.parse("2026-09-27T12:00:00Z");
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, opened)).events());

      AgentState after =
          inferring
              .apply(
                  new AgentEvent.InferenceAttempted(
                      inferring.seq().next(),
                      TURN,
                      new Failure.Transient("busy"),
                      Usage.of("a-model", 40, 0)))
              .apply(
                  new AgentEvent.ActionsRequested(
                      inferring.seq().next().next(),
                      TURN,
                      MAIL,
                      List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool")),
                      Usage.of("a-model", 100, 20)));

      assertThat(after)
          .asInstanceOf(InstanceOfAssertFactories.type(AgentState.AwaitingActions.class))
          .extracting(AgentState.AwaitingActions::stats)
          .satisfies(
              stats -> {
                assertThat(stats.startedAt())
                    .as("from the event, not from a clock")
                    .isEqualTo(opened);
                assertThat(stats.modelCalls()).as("the attempt was a call too").isEqualTo(2);
                assertThat(stats.failedAttempts()).isEqualTo(1);
                assertThat(stats.toolCalls()).isEqualTo(1);
                assertThat(stats.spent())
                    .as("everything, failures included")
                    .isEqualTo(Tokens.of(160));
                assertThat(stats.wasted()).isEqualTo(Tokens.of(40));
                assertThat(stats.productiveTokens()).isEqualTo(Tokens.of(120));
              });
    }

    @Test
    @DisplayName("a call tried again leaves the agent still inferring, one seq further on")
    void a_retried_attempt_does_not_close_the_turn() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());

      AgentState after =
          inferring.apply(
              new AgentEvent.InferenceAttempted(
                  inferring.seq().next(),
                  TURN,
                  new Failure.Transient("the model was busy"),
                  Usage.of("a-model", 11, 0)));

      assertThat(after)
          .as("the call it belongs to has not settled, so the turn is still open")
          .isInstanceOf(AgentState.Inferring.class);
      assertThat(after.seq())
          .as("but the story moved, and the next event must land after it")
          .isEqualTo(inferring.seq().next());
    }

    @Test
    @DisplayName("a turn that stumbled and then answered still ends idle")
    void attempts_do_not_disturb_the_close() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      AgentState stumbled =
          inferring.apply(
              new AgentEvent.InferenceAttempted(
                  inferring.seq().next(),
                  TURN,
                  new Failure.Transient("the model was busy"),
                  Usage.unreported()));

      AgentState after =
          stumbled.applyAll(
              stumbled
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported())))
                  .events());

      assertThat(after).isInstanceOf(AgentState.Idle.class);
    }

    @Test
    @DisplayName("a call that took three goes writes each one down before the answer")
    void attempts_are_written_before_the_event_that_closes_the_call() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      List<FailedAttempt> attempts =
          List.of(
              new FailedAttempt(new Failure.Transient("busy"), Usage.of("a-model", 11, 0)),
              new FailedAttempt(new Failure.Unknown("no answer"), Usage.unreported()));

      Decision decision =
          inferring.execute(
              new AgentCommand.CompleteInference(
                  TURN,
                  new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.of("a-model", 20, 5)),
                  attempts));

      assertThat(decision.events())
          .as("the two tries, then the answer")
          .extracting(event -> event.getClass().getSimpleName())
          .containsExactly("InferenceAttempted", "InferenceAttempted", "InferenceAnswered");
      assertThat(decision.events())
          .as("consecutive from where the state was read, so nothing can land between them")
          .extracting(AgentEvent::seq)
          .containsExactly(
              inferring.seq().next(),
              inferring.seq().next().next(),
              inferring.seq().next().next().next());
      assertThat(inferring.applyAll(decision.events()))
          .as("and the turn still closes")
          .isInstanceOf(AgentState.Idle.class);
    }

    @Test
    @DisplayName("a redelivered answer writes nothing, its attempts included")
    void attempts_are_not_written_twice_by_a_redelivery() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      AgentState closed =
          inferring.applyAll(
              inferring
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported())))
                  .events());

      Decision again =
          closed.execute(
              new AgentCommand.CompleteInference(
                  TURN,
                  new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported()),
                  List.of(new FailedAttempt(new Failure.Transient("busy"), Usage.unreported()))));

      assertThat(again.events())
          .as("the turn is already closed; its attempts are not news a second time")
          .isEmpty();
    }

    @Test
    @DisplayName(
        "an event out of order is refused rather than quietly building a state that never existed")
    void out_of_order_is_refused() {
      AgentState state =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      AgentEvent stale =
          new AgentEvent.InferenceAnswered(
              Seq.NONE, state.seq().opensTurn(), ANSWER, Usage.unreported());

      assertThatThrownBy(() -> state.apply(stale))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("applied to state at");
    }

    @Test
    @DisplayName("the same event twice is refused for the same reason")
    void re_applying_one_event_is_refused() {
      List<AgentEvent> events =
          idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events();
      AgentState once = idle.applyAll(events);

      assertThatThrownBy(() -> once.applyAll(events)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("tool calls")
  class Calls {

    @Test
    @DisplayName("a request for actions asks for approval first, not for the call")
    void actions_are_approved_before_they_run() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());

      Decision decision =
          inferring.execute(
              new AgentCommand.CompleteInference(
                  TURN,
                  new AgentCommand.InferenceOutcome.RequestedActions(
                      MAIL,
                      List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool")),
                      Usage.unreported())));

      assertThat(decision.effects()).singleElement().isInstanceOf(AgentEffect.Approve.class);
    }

    @Test
    @DisplayName("an approval releases the call, and only then")
    void approval_releases_the_call() {
      AgentState awaiting =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      awaiting =
          awaiting.applyAll(
              awaiting
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.RequestedActions(
                              MAIL,
                              List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool")),
                              Usage.unreported())))
                  .events());

      Decision decision =
          awaiting.execute(
              new AgentCommand.CompleteApproval(
                  TURN, CALL, new AgentCommand.ApprovalOutcome.Approved(Optional.empty())));

      assertThat(decision.effects()).singleElement().isInstanceOf(AgentEffect.CallTool.class);
    }

    @Test
    @DisplayName("the last call coming back asks the model again")
    void the_last_call_resumes_the_turn() {
      AgentState running = awaitingOneRunningCall();

      Decision decision =
          running.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, CALL, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")));

      assertThat(decision.effects()).singleElement().isInstanceOf(AgentEffect.Infer.class);
      assertThat(running.applyAll(decision.events())).isInstanceOf(AgentState.Inferring.class);
    }

    @Test
    @DisplayName("a redelivered outcome records nothing")
    void a_redelivered_outcome_is_ignored() {
      AgentState running = awaitingOneRunningCall();
      Decision first =
          running.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, CALL, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")));
      AgentState after = running.applyAll(first.events());

      Decision again =
          after.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, CALL, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")));

      assertThat(again).isInstanceOf(Decision.Ignore.class);
    }

    @Test
    @DisplayName(
        "before approval, an outstanding call's since is the seq of the actions being requested")
    void since_before_approval_is_the_actions_requested_seq() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      Decision requested =
          inferring.execute(
              new AgentCommand.CompleteInference(
                  TURN,
                  new AgentCommand.InferenceOutcome.RequestedActions(
                      MAIL,
                      List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool")),
                      Usage.unreported())));
      AgentState awaiting = inferring.applyAll(requested.events());

      AgentEvent.ActionsRequested actionsRequested =
          (AgentEvent.ActionsRequested) requested.events().getFirst();
      AgentState.AwaitingActions state = (AgentState.AwaitingActions) awaiting;
      assertThat(state.outstanding()).isNotEmpty();
      assertThat(state.outstanding().get(CALL).since()).isEqualTo(actionsRequested.seq());
    }

    @Test
    @DisplayName("once approved, the outstanding call's since moves to the approval's seq")
    void since_after_approval_is_the_tool_approved_seq() {
      AgentState running = awaitingOneRunningCall();

      AgentState.AwaitingActions state = (AgentState.AwaitingActions) running;
      assertThat(state.outstanding()).isNotEmpty();
      assertThat(state.outstanding().get(CALL).since()).isEqualTo(state.seq());
      assertThat(state.outstanding().get(CALL).phase()).isEqualTo(OutstandingAction.Phase.RUNNING);
    }

    @Test
    void the_state_holds_a_calls_id_and_tool_and_not_what_it_would_do() {
      CallId other = new CallId("call-2");
      ToolName otherTool = new ToolName("audit");
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      Decision requested =
          inferring.execute(
              new AgentCommand.CompleteInference(
                  TURN,
                  new AgentCommand.InferenceOutcome.RequestedActions(
                      MAIL,
                      List.of(
                          new ActionRequest.ToolCall(CALL, TOOL, "refund 40 dollars to the buyer"),
                          new ActionRequest.ToolCall(
                              other, otherTool, "audit the buyer's history")),
                      Usage.unreported())));
      AgentState awaiting = inferring.applyAll(requested.events());

      AgentEvent.ActionsRequested actionsRequested =
          (AgentEvent.ActionsRequested) requested.events().getFirst();
      AgentState.AwaitingActions state = (AgentState.AwaitingActions) awaiting;

      assertThat(state.outstanding())
          .isEqualTo(
              Map.of(
                  CALL,
                  OutstandingAction.awaitingApproval(CALL, TOOL, actionsRequested.seq()),
                  other,
                  OutstandingAction.awaitingApproval(other, otherTool, actionsRequested.seq())));
      assertThat(state.toString())
          .doesNotContain("refund 40 dollars to the buyer")
          .doesNotContain("audit the buyer's history");
    }
  }

  @Nested
  @DisplayName("the turn ending without an answer")
  class EndingBadly {

    private AgentState inferring() {
      return idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
    }

    @Test
    @DisplayName("a refusal closes the turn and asks for nothing more")
    void a_refusal_closes_the_turn() {
      AgentState inferring = inferring();

      Decision decision =
          inferring.execute(
              new AgentCommand.CompleteInference(
                  TURN, new AgentCommand.InferenceOutcome.Refused("safety", Usage.unreported())));

      assertThat(decision.effects()).isEmpty();
      assertThat(inferring.applyAll(decision.events())).isInstanceOf(AgentState.Idle.class);
    }

    @Test
    @DisplayName("a failure closes it too, and carries the Failure rather than a sentence")
    void a_failure_closes_the_turn() {
      AgentState inferring = inferring();
      Failure reason = new Failure.Transient("provider unreachable");

      Decision decision =
          inferring.execute(
              new AgentCommand.CompleteInference(
                  TURN, new AgentCommand.InferenceOutcome.Failed(reason, Usage.unreported())));

      assertThat(decision.events())
          .singleElement()
          .asInstanceOf(InstanceOfAssertFactories.type(AgentEvent.InferenceFailed.class))
          .extracting(AgentEvent.InferenceFailed::failure)
          .isEqualTo(reason);
      assertThat(inferring.applyAll(decision.events())).isInstanceOf(AgentState.Idle.class);
    }
  }

  @Nested
  @DisplayName("completions arriving where nothing is waiting for them")
  class Mismatched {

    @Test
    @DisplayName("an inference result for an idle agent records nothing")
    void an_inference_result_on_idle() {
      assertThat(
              idle.execute(
                  new AgentCommand.CompleteInference(
                      TURN,
                      new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported()))))
          .isInstanceOf(Decision.Ignore.class);
    }

    @Test
    @DisplayName("a tool result while the model is being asked records nothing")
    void a_tool_result_while_inferring() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());

      assertThat(
              inferring.execute(
                  new AgentCommand.CompleteToolCall(
                      TURN, CALL, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it"))))
          .isInstanceOf(Decision.Ignore.class);
    }

    @Test
    @DisplayName("an inference result while calls are outstanding records nothing")
    void an_inference_result_while_awaiting_calls() {
      AgentState running = awaitingOneRunningCall();

      assertThat(
              running.execute(
                  new AgentCommand.CompleteInference(
                      TURN,
                      new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported()))))
          .isInstanceOf(Decision.Ignore.class);
    }

    @Test
    @DisplayName("and an Ignore carries nothing to write down")
    void an_ignore_is_empty() {
      Decision ignored =
          idle.execute(
              new AgentCommand.CompleteInference(
                  TURN, new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported())));

      assertThat(ignored.events()).isEmpty();
      assertThat(ignored.effects()).isEmpty();
    }
  }

  @Nested
  @DisplayName("completions that answer another turn")
  class AnotherTurn {

    /** The agent is on turn 2; turn 1 closed a while ago and its answer is only now arriving. */
    private AgentState inferringOnTheSecondTurn() {
      AgentState state = idle;
      state =
          state.applyAll(state.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      state =
          state.applyAll(
              state
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported())))
                  .events());
      return state.applyAll(
          state.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
    }

    @Test
    @DisplayName("an answer stamped with an earlier turn records nothing at all")
    void an_inference_for_an_earlier_turn_is_ignored() {
      AgentState secondTurn = inferringOnTheSecondTurn();
      assertThat(((AgentState.Inferring) secondTurn).turn()).isNotEqualTo(TURN);

      Decision decision =
          secondTurn.execute(
              new AgentCommand.CompleteInference(
                  TURN, new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported())));

      assertThat(decision).isInstanceOf(Decision.Ignore.class);
      assertThat(decision.events())
          .as("written down, it would be this turn's answer and handed to this turn's caller")
          .isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    @DisplayName("and the same answer stamped with the turn actually open is accepted")
    void its_twin_the_matching_turn_is_accepted() {
      AgentState secondTurn = inferringOnTheSecondTurn();
      TurnId open = ((AgentState.Inferring) secondTurn).turn();

      Decision decision =
          secondTurn.execute(
              new AgentCommand.CompleteInference(
                  open, new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported())));

      assertThat(decision.events())
          .singleElement()
          .isInstanceOf(AgentEvent.InferenceAnswered.class);
    }

    @Test
    @DisplayName("an approval stamped with another turn cannot settle a call of this one")
    void an_approval_for_another_turn_is_ignored() {
      AgentState awaiting = idle;
      awaiting =
          awaiting.applyAll(
              awaiting.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      awaiting =
          awaiting.applyAll(
              awaiting
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.RequestedActions(
                              MAIL,
                              List.of(new ActionRequest.ToolCall(CALL, TOOL, "tool")),
                              Usage.unreported())))
                  .events());

      Decision decision =
          awaiting.execute(
              new AgentCommand.CompleteApproval(
                  new TurnId(99),
                  CALL,
                  new AgentCommand.ApprovalOutcome.Approved(Optional.empty())));

      assertThat(decision).isInstanceOf(Decision.Ignore.class);
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }

    @Test
    @DisplayName("nor may a tool result stamped with another turn")
    void a_tool_result_for_another_turn_is_ignored() {
      AgentState running = awaitingOneRunningCall();

      Decision decision =
          running.execute(
              new AgentCommand.CompleteToolCall(
                  new TurnId(99),
                  CALL,
                  new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")));

      assertThat(decision).isInstanceOf(Decision.Ignore.class);
      assertThat(decision.events()).isEmpty();
      assertThat(decision.effects()).isEmpty();
    }
  }

  @Nested
  @DisplayName("facts that cannot have happened")
  class Impossible {

    @Test
    @DisplayName("an answer applied to an idle agent is refused rather than quietly absorbed")
    void an_answer_cannot_land_on_an_idle_agent() {
      AgentEvent answer =
          new AgentEvent.InferenceAnswered(
              Seq.of(1), Seq.of(1).opensTurn(), ANSWER, Usage.unreported());

      assertThatThrownBy(() -> idle.apply(answer))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot happen in Idle");
    }

    @Test
    @DisplayName("a tool outcome applied mid-inference is refused")
    void a_tool_outcome_cannot_land_while_inferring() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      AgentEvent tooEarly =
          new AgentEvent.ToolSucceeded(Seq.of(9), Seq.of(9).opensTurn(), CALL, RESULT, "found it");

      assertThatThrownBy(() -> inferring.apply(tooEarly))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot happen in Inferring");
    }

    @Test
    @DisplayName("an agent waiting for nothing is not a state that can exist")
    void awaiting_nothing_is_rejected() {
      Seq seq = Seq.of(1);
      TurnId turn = seq.opensTurn();
      Map<CallId, OutstandingAction> noActions = Map.of();
      TurnStats stats = TurnStats.opened(Instant.EPOCH);

      assertThatThrownBy(() -> new AgentState.AwaitingActions(seq, turn, seq, noActions, stats))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("awaiting nothing");
    }

    @Test
    @DisplayName("a reference that names nothing is not a reference")
    void a_blank_payload_reference_is_rejected() {
      assertThatThrownBy(() -> PayloadRef.of("  "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("names nothing");
    }
  }

  @Nested
  @DisplayName("several calls at once")
  class Parallel {

    private static final CallId A = new CallId("call-a");
    private static final CallId B = new CallId("call-b");
    private static final CallId C = new CallId("call-c");

    /** A turn where three calls have been requested and all three approved. */
    private AgentState threeRunning() {
      AgentState state = idle;
      state =
          state.applyAll(state.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      state =
          state.applyAll(
              state
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.RequestedActions(
                              MAIL,
                              List.of(
                                  new ActionRequest.ToolCall(A, TOOL, "tool"),
                                  new ActionRequest.ToolCall(B, TOOL, "tool"),
                                  new ActionRequest.ToolCall(C, TOOL, "tool")),
                              Usage.unreported())))
                  .events());
      for (CallId call : List.of(A, B, C)) {
        state =
            state.applyAll(
                state
                    .execute(
                        new AgentCommand.CompleteApproval(
                            TURN,
                            call,
                            new AgentCommand.ApprovalOutcome.Approved(Optional.empty())))
                    .events());
      }
      return state;
    }

    @Test
    @DisplayName("one approval per call is asked for, not one for the batch")
    void every_call_is_approved_separately() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());

      Decision decision =
          inferring.execute(
              new AgentCommand.CompleteInference(
                  TURN,
                  new AgentCommand.InferenceOutcome.RequestedActions(
                      MAIL,
                      List.of(
                          new ActionRequest.ToolCall(A, TOOL, "tool"),
                          new ActionRequest.ToolCall(B, TOOL, "tool")),
                      Usage.unreported())));

      assertThat(decision.effects()).hasSize(2).allMatch(AgentEffect.Approve.class::isInstance);
    }

    @Test
    @DisplayName("the model is not asked again until the last call is back")
    void only_the_last_discharge_resumes_the_turn() {
      AgentState state = threeRunning();

      Decision first =
          state.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, A, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")));
      assertThat(first.effects()).isEmpty();
      state = state.applyAll(first.events());

      Decision second =
          state.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, B, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")));
      assertThat(second.effects()).isEmpty();
      state = state.applyAll(second.events());

      Decision last =
          state.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, C, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")));
      assertThat(last.effects()).singleElement().isInstanceOf(AgentEffect.Infer.class);
      assertThat(state.applyAll(last.events())).isInstanceOf(AgentState.Inferring.class);
    }

    @Test
    @DisplayName("order does not matter: whichever comes back last resumes the turn")
    void discharge_order_is_irrelevant() {
      AgentState state = threeRunning();

      for (CallId call : List.of(C, A)) {
        state =
            state.applyAll(
                state
                    .execute(
                        new AgentCommand.CompleteToolCall(
                            TURN, call, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it")))
                    .events());
      }

      Decision last =
          state.execute(
              new AgentCommand.CompleteToolCall(
                  TURN, B, new AgentCommand.ToolOutcome.Failed("nope")));

      assertThat(last.effects()).singleElement().isInstanceOf(AgentEffect.Infer.class);
    }

    @Test
    @DisplayName("a denial discharges its call like any other outcome")
    void a_denial_discharges_the_call() {
      AgentState state = idle;
      state =
          state.applyAll(state.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      state =
          state.applyAll(
              state
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.RequestedActions(
                              MAIL,
                              List.of(new ActionRequest.ToolCall(A, TOOL, "tool")),
                              Usage.unreported())))
                  .events());

      Decision denied =
          state.execute(
              new AgentCommand.CompleteApproval(
                  TURN, A, new AgentCommand.ApprovalOutcome.Denied("policy", Optional.empty())));

      assertThat(denied.effects()).singleElement().isInstanceOf(AgentEffect.Infer.class);
      assertThat(state.applyAll(denied.events())).isInstanceOf(AgentState.Inferring.class);
    }

    @Test
    @DisplayName("a completion for a call that was never requested records nothing")
    void an_unknown_call_is_ignored() {
      AgentState state = threeRunning();

      assertThat(
              state.execute(
                  new AgentCommand.CompleteToolCall(
                      TURN,
                      new CallId("nobody"),
                      new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it"))))
          .isInstanceOf(Decision.Ignore.class);
    }

    @Test
    @DisplayName("a tool result for a call still awaiting approval records nothing")
    void a_result_before_approval_is_ignored() {
      AgentState state = idle;
      state =
          state.applyAll(state.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      state =
          state.applyAll(
              state
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.RequestedActions(
                              MAIL,
                              List.of(new ActionRequest.ToolCall(A, TOOL, "tool")),
                              Usage.unreported())))
                  .events());

      assertThat(
              state.execute(
                  new AgentCommand.CompleteToolCall(
                      TURN, A, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it"))))
          .isInstanceOf(Decision.Ignore.class);
    }
  }

  @Nested
  @DisplayName("termination")
  class Termination {

    @Test
    void terminating_an_idle_agent_records_it() {
      Decision decision = idle.execute(new AgentCommand.Terminate());

      assertThat(decision.events()).singleElement().isInstanceOf(AgentEvent.Terminated.class);
    }

    @Test
    @DisplayName("mid-turn, terminate records nothing: the turn in flight has to finish")
    void terminate_mid_turn_is_held() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());

      assertThat(inferring.execute(new AgentCommand.Terminate()))
          .isInstanceOf(Decision.Ignore.class);
    }

    @Test
    @DisplayName(
        "with calls outstanding it is held too: effects already have rows and are owed outcomes")
    void terminate_with_calls_outstanding_is_held() {
      AgentState running = awaitingOneRunningCall();

      assertThat(running.execute(new AgentCommand.Terminate())).isInstanceOf(Decision.Ignore.class);
    }

    @Test
    @DisplayName("and it takes once the turn closes, which is when the harness asks again")
    void terminate_takes_once_the_turn_closes() {
      AgentState inferring =
          idle.applyAll(idle.execute(new AgentCommand.StartTurn(MAIL, Instant.EPOCH)).events());
      AgentState after =
          inferring.applyAll(
              inferring
                  .execute(
                      new AgentCommand.CompleteInference(
                          TURN,
                          new AgentCommand.InferenceOutcome.Answered(ANSWER, Usage.unreported())))
                  .events());

      assertThat(after).isInstanceOf(AgentState.Idle.class);
      assertThat(after.applyAll(after.execute(new AgentCommand.Terminate()).events()))
          .isInstanceOf(AgentState.Terminal.class);
    }

    @Test
    @DisplayName("a terminated agent refuses to start a turn, loudly")
    void terminal_refuses_work() {
      AgentState dead = idle.applyAll(idle.execute(new AgentCommand.Terminate()).events());
      AgentCommand.StartTurn startTurn = new AgentCommand.StartTurn(MAIL, Instant.EPOCH);

      assertThatThrownBy(() -> dead.execute(startTurn))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("accepts nothing further");
    }

    @Test
    @DisplayName("a terminated agent refuses a second terminate, loudly")
    void terminal_refuses_terminate() {
      AgentState dead = idle.applyAll(idle.execute(new AgentCommand.Terminate()).events());
      AgentCommand.Terminate terminate = new AgentCommand.Terminate();

      assertThatThrownBy(() -> dead.execute(terminate))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("accepts nothing further");
    }

    @Test
    @DisplayName("but a redelivered outcome is not a caller's mistake, so it is silent")
    void terminal_ignores_a_late_outcome() {
      AgentState dead = idle.applyAll(idle.execute(new AgentCommand.Terminate()).events());

      assertThat(
              dead.execute(
                  new AgentCommand.CompleteToolCall(
                      TURN, CALL, new AgentCommand.ToolOutcome.Succeeded(RESULT, "found it"))))
          .isInstanceOf(Decision.Ignore.class);
    }

    @Test
    @DisplayName("nothing moves a terminated agent, which is what makes termination irreversible")
    void terminal_is_a_dead_end() {
      AgentState dead = idle.applyAll(idle.execute(new AgentCommand.Terminate()).events());
      AgentEvent later =
          new AgentEvent.TurnStarted(Seq.of(99), Seq.of(99).opensTurn(), MAIL, Instant.EPOCH);

      assertThatThrownBy(() -> dead.apply(later)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("and it has no position, because nothing is ever minted from it")
    void terminal_has_no_position() {
      AgentState dead = idle.applyAll(idle.execute(new AgentCommand.Terminate()).events());

      assertThatThrownBy(dead::seq)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no position");
    }
  }
}
