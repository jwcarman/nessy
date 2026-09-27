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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.agent.OutstandingAction;

/**
 * The whole of the pure core: where an agent is, what a command means there, and what a fact does
 * to it.
 *
 * <p><b>Nothing here does I/O</b>, reads a clock, or is random. Four separate things break if that
 * stops being true: a seq conflict re-runs {@link #execute} so a side effect would happen twice;
 * {@link #apply} runs over the whole turn on every command, so non-determinism means the state you
 * rebuild is not the state you had; the atomicity invariant dies if slow work drifts in here; and a
 * workflow-engine driver stops being possible. The pressure to break it will arrive reasonably -- a
 * clock, a config lookup, a feature flag -- and the first one costs all four.
 *
 * <p><b>No generics.</b> The caller's own input type stops at the harness, which renders and
 * claim-checks before a command gets here. That is what makes every type in the event stream one of
 * Nessy's own, which in turn is what makes versioning a persisted stream tractable.
 *
 * <p><b>Reconstitution is a loop, not a concept.</b> Replay events onto {@link #idle} and the state
 * you arrive at is the thing that handles the next command. There is no "fold" to name.
 *
 * <p><b>Only {@link Idle} accepts a command from outside.</b> {@code StartTurn} and {@code
 * Terminate} are both held by the harness and presented when the agent can take them; a busy state
 * accepts only the completion of work it is already waiting for. That is one rule rather than two,
 * and it is what removes the need for a state that means "terminating, but finishing first".
 *
 * <p>A busy state answering with {@link Decision.Ignore} is not the mechanism -- the harness not
 * presenting is. The refusal is what makes a race harmless when two harnesses both read an idle
 * agent and both ask.
 */
public sealed interface AgentState {

  /** Where this state sits. New events must come strictly after it. */
  Seq seq();

  /** An agent with nothing since the watermark. */
  static AgentState idle(Seq at) {
    return new Idle(at);
  }

  /**
   * One fact, applied.
   *
   * <p>Guards the order and then delegates. The guard is here rather than in {@link #applyAll}
   * because this is the path a harness uses most -- a command's own events, one at a time -- and a
   * guard only on the bulk path would leave it open.
   *
   * <p>Out-of-order replay does not fail on its own; it silently produces a state that never
   * existed, and everything downstream trusts reconstitution. Nothing about a list of events tells
   * a caller it must be sorted, and a merged query, a hand-built list or a {@code Set} will not be.
   * Rejecting anything not strictly after also rejects applying the same event twice, which is a
   * real replay hazard and otherwise silent.
   */
  default AgentState apply(AgentEvent event) {
    if (event.seq().compareTo(seq()) <= 0) {
      throw new IllegalArgumentException(
          "event at " + event.seq() + " applied to state at " + seq());
    }
    return accept(event);
  }

  /**
   * What this state does with a fact it has already been told is in order.
   *
   * <p>Separate from {@link #apply} so the guard cannot be forgotten by an arm. A check each arm
   * has to remember is a check that will eventually be missed.
   */
  AgentState accept(AgentEvent event);

  /**
   * Replay, in order.
   *
   * <p>Deliberately not {@code reduce}: that needs a combiner for a fold whose combiner cannot be
   * correct, and would let somebody add {@code parallel()} to an order-dependent replay.
   */
  default AgentState applyAll(List<AgentEvent> events) {
    AgentState state = this;
    for (AgentEvent event : events) {
      state = state.apply(event);
    }
    return state;
  }

  /** What this command means here. */
  Decision execute(AgentCommand command);

  // ---------------------------------------------------------------------------------------------

  /** Between turns, and willing to start one. */
  record Idle(Seq seq) implements AgentState {

    @Override
    public AgentState accept(AgentEvent event) {
      return switch (event) {
        case AgentEvent.TurnStarted started -> new Inferring(started.seq(), started.turn());
        // Only here: Terminate is accepted only by Idle, so this fact can only ever follow an
        // idle agent. A busy arm carrying this case would claim something that cannot happen.
        case AgentEvent.Terminated _ -> new Terminal();
        default -> throw unexpected(event, this);
      };
    }

    @Override
    public Decision execute(AgentCommand command) {
      return switch (command) {
        case AgentCommand.StartTurn start -> {
          Seq at = seq.next();
          TurnId opened = at.opensTurn();
          yield Decision.of(
              List.of(new AgentEvent.TurnStarted(at, opened, start.input())),
              List.of(new AgentEffect.Infer(opened)));
        }
        case AgentCommand.Terminate _ ->
            Decision.of(List.of(new AgentEvent.Terminated(seq.next())), List.of());
        // An outcome for work this agent is not doing. At-least-once delivery means a late
        // redelivery can land here after the turn it belonged to closed; it is not an error and
        // nothing should be written down.
        default -> Decision.ignore();
      };
    }
  }

  /** A turn is open and the model has been asked. */
  record Inferring(Seq seq, TurnId turn) implements AgentState {

    @Override
    public AgentState accept(AgentEvent event) {
      return switch (event) {
        case AgentEvent.InferenceAnswered answered -> new Idle(answered.seq());
        case AgentEvent.InferenceRefused refused -> new Idle(refused.seq());
        case AgentEvent.InferenceFailed failed -> new Idle(failed.seq());
        case AgentEvent.ActionsRequested requested -> AwaitingActions.opening(requested);
        default -> throw unexpected(event, this);
      };
    }

    @Override
    public Decision execute(AgentCommand command) {
      return switch (command) {
        // An answer to a turn that is not the one open here. At-least-once delivery and a door
        // that lets go of its lock between steps mean a completion can arrive after the turn it
        // belonged to was closed by somebody else -- by recovery, or by a redelivery that got
        // there first. Stamping it with this turn would write down an answer to a question this
        // turn never asked and hand it to the caller driving this turn.
        case AgentCommand.CompleteInference done when !done.turn().equals(turn) ->
            Decision.ignore();
        case AgentCommand.CompleteInference done -> completed(done);
        // Busy. Both external commands are held by the harness and presented when this turn
        // closes -- work already in flight is owed its outcome, and abandoning it here would
        // leave effects with rows and nothing to deliver them to.
        case AgentCommand.StartTurn _, AgentCommand.Terminate _ -> Decision.ignore();
        default -> Decision.ignore();
      };
    }

    /** The permission an action needs before anything is done in the world on its behalf. */
    private static AgentEffect approving(TurnId turn, Seq at, ActionRequest action) {
      return switch (action) {
        case ActionRequest.ToolCall call ->
            new AgentEffect.Approve(turn, at, call.id(), call.name());
      };
    }

    private Decision completed(AgentCommand.CompleteInference done) {
      Seq at = seq.next();
      return switch (done.outcome()) {
        case AgentCommand.InferenceOutcome.Answered answered ->
            Decision.of(
                List.of(
                    new AgentEvent.InferenceAnswered(
                        at, turn, answered.answer(), answered.usage())),
                List.of());
        case AgentCommand.InferenceOutcome.Refused refused ->
            Decision.of(
                List.of(
                    new AgentEvent.InferenceRefused(at, turn, refused.category(), refused.usage())),
                List.of());
        case AgentCommand.InferenceOutcome.Failed failed ->
            Decision.of(
                List.of(new AgentEvent.InferenceFailed(at, turn, failed.failure(), failed.usage())),
                List.of());
        case AgentCommand.InferenceOutcome.RequestedActions asked ->
            Decision.of(
                List.of(
                    new AgentEvent.ActionsRequested(
                        at, turn, asked.request(), asked.actions(), asked.usage())),
                asked.actions().stream().map(action -> approving(turn, at, action)).toList());
      };
    }
  }

  /**
   * A turn is open and calls are outstanding.
   *
   * @param seq where this state sits, which moves with every event it accepts
   * @param requestSeq where the request that asked for these calls sits, which does not move. An
   *     effect naming a call has to name that request too, because the call itself is content
   *     behind it -- and by the time an approval comes back, {@code seq} is the approval's.
   */
  record AwaitingActions(
      Seq seq, TurnId turn, Seq requestSeq, Map<CallId, OutstandingAction> outstanding)
      implements AgentState {

    public AwaitingActions {
      if (outstanding.isEmpty()) {
        // Nothing would ever arrive to move this on, so the turn would stay open forever. The
        // state goes back to inferring the moment the last call is discharged.
        throw new IllegalArgumentException("an agent awaiting nothing is not awaiting");
      }
      outstanding = Map.copyOf(outstanding);
    }

    static AwaitingActions opening(AgentEvent.ActionsRequested requested) {
      Map<CallId, OutstandingAction> calls = new LinkedHashMap<>();
      for (ActionRequest action : requested.actions()) {
        calls.put(action.id(), OutstandingAction.awaitingApproval(action, requested.seq()));
      }
      return new AwaitingActions(requested.seq(), requested.turn(), requested.seq(), calls);
    }

    @Override
    public AgentState accept(AgentEvent event) {
      return switch (event) {
        case AgentEvent.ToolApproved approved -> running(approved.seq(), approved.callId());
        case AgentEvent.ToolDenied denied -> discharge(denied.seq(), denied.callId());
        case AgentEvent.ToolSucceeded succeeded -> discharge(succeeded.seq(), succeeded.callId());
        case AgentEvent.ToolFailed failed -> discharge(failed.seq(), failed.callId());
        default -> throw unexpected(event, this);
      };
    }

    private AgentState running(Seq at, CallId callId) {
      OutstandingAction call = outstanding.get(callId);
      if (call == null) {
        throw new IllegalArgumentException("no outstanding call " + callId);
      }
      Map<CallId, OutstandingAction> next = new LinkedHashMap<>(outstanding);
      next.put(callId, call.running(at));
      return new AwaitingActions(at, turn, requestSeq, next);
    }

    /** One fewer thing to wait for -- and back to inferring when it was the last. */
    private AgentState discharge(Seq at, CallId callId) {
      Map<CallId, OutstandingAction> next = new LinkedHashMap<>(outstanding);
      next.remove(callId);
      return next.isEmpty()
          ? new Inferring(at, turn)
          : new AwaitingActions(at, turn, requestSeq, next);
    }

    @Override
    public Decision execute(AgentCommand command) {
      return switch (command) {
        // As in Inferring: an outcome that names another turn settles nothing here. The call id
        // alone is not enough -- ids come from the model and a later turn can reuse one.
        case AgentCommand.CompleteApproval done when !done.turn().equals(turn) -> Decision.ignore();
        case AgentCommand.CompleteToolCall done when !done.turn().equals(turn) -> Decision.ignore();
        case AgentCommand.CompleteApproval done -> approved(done);
        case AgentCommand.CompleteToolCall done -> ran(done);
        // As in Inferring: held by the harness until the turn closes.
        case AgentCommand.StartTurn _, AgentCommand.Terminate _ -> Decision.ignore();
        default -> Decision.ignore();
      };
    }

    private Decision approved(AgentCommand.CompleteApproval done) {
      OutstandingAction call = outstanding.get(done.callId());
      // Already discharged, or never ours: a redelivery. Nothing happened.
      if (call == null || call.phase() != OutstandingAction.Phase.AWAITING_APPROVAL) {
        return Decision.ignore();
      }
      Seq at = seq.next();
      return switch (done.outcome()) {
        case AgentCommand.ApprovalOutcome.Approved ok ->
            Decision.of(
                List.of(new AgentEvent.ToolApproved(at, turn, done.callId(), ok.reference())),
                List.of(performing(turn, requestSeq, call.action())));
        case AgentCommand.ApprovalOutcome.Denied no ->
            Decision.of(
                List.of(
                    new AgentEvent.ToolDenied(
                        at, turn, done.callId(), no.reason(), no.reference())),
                nextInference(1));
      };
    }

    private Decision ran(AgentCommand.CompleteToolCall done) {
      OutstandingAction call = outstanding.get(done.callId());
      // Already discharged, or never ours: a redelivery.
      if (call == null) {
        return Decision.ignore();
      }
      // An answer has to fit the action it claims to settle. A tool outcome can only discharge a
      // tool call -- when a second kind of action exists, its answer arriving at this id would
      // otherwise settle something it knows nothing about.
      if (!(call.action() instanceof ActionRequest.ToolCall)) {
        return Decision.ignore();
      }
      // A result can only come from a call that was running. A FAILURE can reach one that never
      // got that far: an approval that expires discharges its call as failed rather than denied,
      // because nobody said no -- and refusing it here because the call never ran would leave the
      // turn open with nothing left that could ever close it.
      if (done.outcome() instanceof AgentCommand.ToolOutcome.Succeeded
          && call.phase() != OutstandingAction.Phase.RUNNING) {
        return Decision.ignore();
      }
      Seq at = seq.next();
      AgentEvent event =
          switch (done.outcome()) {
            case AgentCommand.ToolOutcome.Succeeded ok ->
                new AgentEvent.ToolSucceeded(at, turn, done.callId(), ok.result());
            case AgentCommand.ToolOutcome.Failed no ->
                new AgentEvent.ToolFailed(at, turn, done.callId(), no.message());
          };
      return Decision.of(List.of(event), nextInference(1));
    }

    /** The work that performs an action, whatever kind of action it is. */
    private static AgentEffect performing(TurnId turn, Seq requestSeq, ActionRequest action) {
      return switch (action) {
        case ActionRequest.ToolCall call ->
            new AgentEffect.CallTool(turn, requestSeq, call.id(), call.name());
      };
    }

    /** Ask the model again once this discharge leaves nothing outstanding. */
    private List<AgentEffect> nextInference(int discharging) {
      return outstanding.size() == discharging ? List.of(new AgentEffect.Infer(turn)) : List.of();
    }
  }

  /**
   * A dead end.
   *
   * <p>Reached by applying {@link AgentEvent.Terminated}, and reachable from nowhere -- a state
   * only moves on events, and this one produces none. Termination is therefore irreversible by
   * construction rather than by a flag the surrounding code must remember to check.
   *
   * <p>It refuses commands <b>loudly</b>. Silently swallowing a command sent to a dead agent is the
   * failure that costs somebody an afternoon. The exception is a redelivered outcome, which is
   * at-least-once machinery working correctly rather than a caller's mistake, and which every other
   * state also answers with nothing.
   */
  record Terminal() implements AgentState {

    /**
     * There is no position here, because nothing is ever minted from it. Asking is a sign the
     * caller thinks this agent still has somewhere to go.
     */
    @Override
    public Seq seq() {
      throw new IllegalStateException("a terminated agent has no position");
    }

    /** Refuses before the order guard rather than after: there is nothing to be in order with. */
    @Override
    public AgentState apply(AgentEvent event) {
      throw new IllegalStateException("a terminated agent accepts nothing further: " + event);
    }

    @Override
    public AgentState accept(AgentEvent event) {
      throw new IllegalStateException("a terminated agent accepts nothing further: " + event);
    }

    @Override
    public Decision execute(AgentCommand command) {
      return switch (command) {
        case AgentCommand.StartTurn _, AgentCommand.Terminate _ ->
            throw new IllegalStateException(
                "a terminated agent accepts nothing further: " + command);
        default -> Decision.ignore();
      };
    }
  }

  private static IllegalArgumentException unexpected(AgentEvent event, AgentState state) {
    return new IllegalArgumentException(
        event.getClass().getSimpleName() + " cannot happen in " + state.getClass().getSimpleName());
  }
}
