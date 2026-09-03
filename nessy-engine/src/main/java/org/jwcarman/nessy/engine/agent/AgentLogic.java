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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.tool.ApprovalResult;

/**
 * Every rule about what an agent does next, and no way to do any of it.
 *
 * <p>Pure by construction: no clock, no store, no actor, no Pekko import. That is what lets a
 * three-day parked approval and a crash mid-model-call be ordinary unit tests rather than a
 * cluster, a race and a fifteen-second timeout.
 */
public final class AgentLogic {

  private AgentLogic() {}

  public static Decision decide(AgentState state, Input input) {
    return switch (input) {
      case Input.BacklogUpdated() -> onBacklogUpdated(state);
      case Input.WorkTaken taken -> onWorkTaken(state, taken);
      case Input.Recovered() -> onRecovered(state);
      case Input.NoWork() -> Decision.of(state.finished(), new Instruction.Sleep());
      case Input.ModelAnswered.Answered(var stopReason, var usage) ->
          endTurn(state.spending(usage), resultOf(stopReason), new Instruction.Remember.Answer());
      case Input.ModelAnswered.Asked asked -> onAsked(state.spending(asked.usage()), asked);
      case Input.ModelAnswered.Refused(var category, var explanation, var usage) ->
          endTurn(state.spending(usage), new TurnResult.Refused(category, explanation));
      case Input.ModelFailed(var reason) -> endTurn(state, new TurnResult.Failed(reason));
      case Input.ApprovalGiven given -> onApproval(state, given);
      case Input.ToolParked(var callId, var expiresAt) ->
          Decision.of(
              state.at(state.working().with(callId, new CallState.Parked())),
              new Instruction.SetAlarm(callId, expiresAt));
      case Input.ToolCompleted(var callId) ->
          settle(state, callId, new Instruction.Narrate.ToolCallCompleted(callId));
      case Input.DeadlinePassed(var callId) -> settle(state, callId);
      case Input.Poisoned() -> onPoisoned(state);
      default -> Decision.nothing(state);
    };
  }

  /**
   * A busy agent drops this on the floor, and that is the point: going idle always ends with a
   * take, so missing the signal costs nothing when a signal-free path reaches the same place.
   */
  private static Decision onBacklogUpdated(AgentState state) {
    // Only from Idle. Asking again while a take is already outstanding is the duplicate this
    // phase exists to prevent; a turn in flight will ask for itself when it ends.
    return state.phase() instanceof Phase.Idle
        ? Decision.of(state.asking(), new Instruction.TakeWork())
        : Decision.nothing(state);
  }

  /**
   * The backlog handed back a poison pill instead of work.
   *
   * <p>It can only arrive as a reply to a take, which is what makes this safe: a reply to a take
   * cannot come back before the batch that asked for it has run, so the turn that just ended has
   * finished writing itself down. The old Forget message had no such ordering — it went straight to
   * the actor, past the only queue this agent's work is sequenced against, and its delete could
   * overtake the answer it was supposed to follow.
   */
  private static Decision onPoisoned(AgentState state) {
    // Everything that was queued dies with it: an agent on its way out does not run its remaining
    // work, because those side effects would outlive the record of having caused them.
    return Decision.of(state.finished(), new Instruction.Forget(), new Instruction.Sleep());
  }

  /**
   * Starts a turn — unless one is already running.
   *
   * <p>The guard is not paranoia. Two takes can be in flight at once, because an agent asks for
   * work on activation AND when told the backlog changed, and both instructions are issued before
   * either answer comes back. The second take finds the row already marked taken and hands back the
   * SAME one, exactly as it is meant to — so without this, one observation would start two turns.
   */
  private static Decision onWorkTaken(AgentState state, Input.WorkTaken taken) {
    // Only a reply to an outstanding ask starts a turn. A reply arriving in any other phase is a
    // duplicate -- take is stranded-first, so it names the row this agent is already working.
    if (!(state.phase() instanceof Phase.AwaitingWork)) {
      return Decision.nothing(state);
    }
    return Decision.of(
        state.taking(taken.turnId(), taken.observationClaim()),
        new Instruction.Narrate.TurnStarted(taken.turnId()),
        new Instruction.Remember.Input(),
        new Instruction.CallModel());
  }

  /**
   * Every call starts out being approved, even one whose binding grants it outright — the approver
   * answers immediately in that case. One path through the code is worth more than the message it
   * would save, and it is the path recovery has to work on too.
   */
  private static Decision onAsked(AgentState state, Input.ModelAnswered.Asked asked) {
    Map<CallId, CallState> calls = new LinkedHashMap<>();
    List<Instruction> then = new ArrayList<>();
    for (Input.CallSummary call : asked.calls()) {
      calls.put(call.callId(), new CallState.Approving(call.toolName()));
      then.add(new Instruction.AskApprover(call.callId(), call.toolName()));
    }
    return new Decision(state.at(new Phase.WorkingTools(calls)), then);
  }

  /**
   * The one exit from a turn.
   *
   * <p>Remember before release, because releasing drops the claims the exchange is written from;
   * and take again at the end, because an agent that finishes without asking for the next piece of
   * work is an agent that needs a nudge to notice work it already has.
   */
  private static Decision endTurn(AgentState state, TurnResult result, Instruction... remembering) {
    List<Instruction> then = new ArrayList<>(List.of(remembering));
    then.add(new Instruction.Narrate.TurnEnded(result, state.usage()));
    then.add(new Instruction.Release());
    // The one place a busy agent's forget is honoured: it asked to go, and now it can.
    // Always take. A forget is a row this take will find, not a flag this decision has to check.
    then.add(new Instruction.TakeWork());
    return new Decision(state.asking(), then);
  }

  /**
   * A turn ending is not a model call ending: {@code TOOL_USE} is the middle of a turn, never its
   * end, so it cannot arrive here — an {@code Asked} would have been sent instead.
   */
  private static TurnResult resultOf(StopReason stopReason) {
    return switch (stopReason) {
      case END_TURN -> new TurnResult.Completed();
      case MAX_TOKENS -> new TurnResult.Truncated();
      case TOOL_USE ->
          throw new IllegalStateException("a tool-use stop is an Asked, not an Answered");
    };
  }

  private static Decision onApproval(AgentState state, Input.ApprovalGiven given) {
    // An approval can arrive for a call that already ended -- the deadline denied it a moment ago,
    // or a person answered twice. Neither is a caller's mistake, and neither should end the agent.
    if (!awaiting(state, given.callId())) {
      return Decision.nothing(state);
    }
    Instruction narrate = new Instruction.Narrate.ApprovalDecided(given.callId(), given.result());
    if (given.result() instanceof ApprovalResult.Approved) {
      return Decision.of(
          state.at(state.working().with(given.callId(), new CallState.Running(given.toolName()))),
          narrate,
          new Instruction.RunTool(given.callId(), given.toolName()));
    }
    // A denial is a COMPLETED call with a result of its own, not a failed turn: the model is told
    // it was refused and gets to decide what to do about that.
    return settle(state, given.callId(), narrate);
  }

  /**
   * Whether this agent is still waiting to hear about that call.
   *
   * <p>It can be told twice. A parked call has a TERM, and the engine denies it on the person's
   * behalf when that term expires — so an answer from a page arriving just after the deadline fired
   * is ordinary traffic, not a broken caller. Before this, the second one asked a turn that had
   * moved on for its tool calls and stopped the agent with "not working tools: CallingModel".
   *
   * <p>Same shape as a duplicate {@code WorkTaken}: news this agent has already had.
   */
  private static boolean awaiting(AgentState state, CallId callId) {
    return state.phase() instanceof Phase.WorkingTools(var calls)
        && calls.containsKey(callId)
        && !(calls.get(callId) instanceof CallState.Completed);
  }

  /**
   * One call reaches its end. When it is the last one, the exchange goes back to the model — which
   * is the only way a turn moves from working tools to calling the model.
   *
   * <p><b>Cancelling the alarm belongs HERE, not at the call sites.</b> It used to be passed in by
   * the one path that remembered — tool completion — so a call that ended any other way kept its
   * deadline. A denial from a desk was the visible case: the row outlived the decision, and because
   * {@code ReminderSweep} RE-ARMS every reminder it fires, that row would wake this agent about a
   * settled call every backoff, forever. Measured on a live watchman: an approval denied at 11:00
   * still held an alarm for three days later.
   *
   * <p>A call that has ended has no deadline, whichever way it ended, so the only place that cannot
   * forget is the place that ends it. Cancelling an alarm that was never armed is silent, so the
   * paths that never parked pay nothing.
   */
  private static Decision settle(AgentState state, CallId callId, Instruction... also) {
    if (!awaiting(state, callId)) {
      return Decision.nothing(state);
    }
    Phase.WorkingTools next = state.working().with(callId, new CallState.Completed());
    List<Instruction> then = new ArrayList<>();
    then.add(new Instruction.CancelAlarm(callId));
    then.addAll(List.of(also));
    if (next.allSettled()) {
      then.add(new Instruction.Remember.Exchange());
      then.add(new Instruction.CallModel());
      return new Decision(state.at(new Phase.CallingModel()), then);
    }
    return new Decision(state.at(next), then);
  }

  /**
   * There is no "should we re-drive?" decision anywhere in the engine. Pekko reads the document
   * before any command, and the agent feeds itself this on EVERY activation — so the rare path is
   * the common path, exercised constantly rather than only after a crash.
   *
   * <p>That matters more than it sounds. The engine's old resumeTools re-ran any call without a
   * stored result, PARKED ones included, so a restart silently re-asked a person and invalidated
   * the reply token already sitting in their inbox. It went unnoticed for exactly as long as
   * nothing ordinary ran it.
   */
  private static Decision onRecovered(AgentState state) {
    return switch (state.phase()) {
      case Phase.Idle() -> Decision.of(state.asking(), new Instruction.TakeWork());
      // The ask itself did not survive the crash, so ask again. Safe because take is
      // stranded-first: it hands back the row this agent already holds rather than a new one.
      case Phase.AwaitingWork() -> Decision.of(state, new Instruction.TakeWork());
      case Phase.CallingModel() -> Decision.of(state, new Instruction.CallModel());
      case Phase.WorkingTools working -> new Decision(state, resume(working));
    };
  }

  private static List<Instruction> resume(Phase.WorkingTools working) {
    List<Instruction> then = new ArrayList<>();
    working
        .calls()
        .forEach(
            (callId, call) -> {
              switch (call) {
                // Asking is idempotent, so ask again.
                case CallState.Approving(var toolName) ->
                    then.add(new Instruction.AskApprover(callId, toolName));
                // Nobody else will answer this one. Tool execution is at-least-once by contract.
                case CallState.Running(var toolName) ->
                    then.add(new Instruction.RunTool(callId, toolName));
                // Someone holds a reply token and an alarm is armed. Re-asking would mint a second.
                case CallState.Parked() -> {
                  // deliberately nothing
                }
                // Its result is in claims.
                case CallState.Completed() -> {
                  // deliberately nothing
                }
              }
            });
    return then;
  }
}
