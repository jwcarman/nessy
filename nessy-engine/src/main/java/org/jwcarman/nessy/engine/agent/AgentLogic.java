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
      case Input.BacklogUpdated ignored -> onBacklogUpdated(state);
      case Input.WorkTaken taken -> onWorkTaken(state, taken);
      case Input.Recovered ignored -> onRecovered(state);
      case Input.NoWork ignored -> Decision.of(state, new Instruction.Sleep());
      case Input.ModelAnswered.Answered answered ->
          endTurn(
              state.spending(answered.usage()),
              resultOf(answered.stopReason()),
              new Instruction.Remember.Answer());
      case Input.ModelAnswered.Asked asked -> onAsked(state.spending(asked.usage()), asked);
      case Input.ModelAnswered.Refused refused ->
          endTurn(
              state.spending(refused.usage()),
              new TurnResult.Refused(refused.category(), refused.explanation()));
      case Input.ModelFailed failed -> endTurn(state, new TurnResult.Failed(failed.reason()));
      case Input.ApprovalGiven given -> onApproval(state, given);
      case Input.ToolParked parked ->
          Decision.of(
              state.at(state.working().with(parked.callId(), new CallState.Parked())),
              new Instruction.SetAlarm(parked.callId(), parked.expiresAt()));
      case Input.ToolCompleted done ->
          settle(
              state,
              done.callId(),
              new Instruction.CancelAlarm(done.callId()),
              new Instruction.Narrate.ToolCallCompleted(done.callId()));
      case Input.DeadlinePassed passed -> settle(state, passed.callId());
      default -> Decision.nothing(state);
    };
  }

  /**
   * A busy agent drops this on the floor, and that is the point: going idle always ends with a
   * take, so missing the signal costs nothing when a signal-free path reaches the same place.
   */
  private static Decision onBacklogUpdated(AgentState state) {
    return state.busy() ? Decision.nothing(state) : Decision.of(state, new Instruction.TakeWork());
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
    if (state.busy()) {
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
    Map<String, CallState> calls = new LinkedHashMap<>();
    List<Instruction> then = new ArrayList<>();
    for (Input.CallSummary call : asked.calls()) {
      calls.put(call.callId(), new CallState.Approving(call.toolName()));
      then.add(new Instruction.Narrate.ToolCallRequested(call.callId(), call.toolName()));
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
    then.add(new Instruction.TakeWork());
    return new Decision(state.finished(), then);
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
   * One call reaches its end. When it is the last one, the exchange goes back to the model — which
   * is the only way a turn moves from working tools to calling the model.
   */
  private static Decision settle(AgentState state, String callId, Instruction... also) {
    Phase.WorkingTools next = state.working().with(callId, new CallState.Completed());
    List<Instruction> then = new ArrayList<>(List.of(also));
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
      case Phase.Idle ignored -> Decision.of(state, new Instruction.TakeWork());
      case Phase.CallingModel ignored -> Decision.of(state, new Instruction.CallModel());
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
                case CallState.Approving approving ->
                    then.add(new Instruction.AskApprover(callId, approving.toolName()));
                // Nobody else will answer this one. Tool execution is at-least-once by contract.
                case CallState.Running running ->
                    then.add(new Instruction.RunTool(callId, running.toolName()));
                // Someone holds a reply token and an alarm is armed. Re-asking would mint a second.
                case CallState.Parked ignored -> {
                  // deliberately nothing
                }
                // Its result is in claims.
                case CallState.Completed ignored -> {
                  // deliberately nothing
                }
              }
            });
    return then;
  }
}
