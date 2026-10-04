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
package org.jwcarman.nessy.console;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;

/**
 * What the REPL prints while the agent works, and how it knows a turn is over.
 *
 * <p>A {@link NarrationListener} is engine-wide -- one sink hears every agent -- so this filters to
 * the one agent the terminal is talking to and ignores the rest. It is built before the engine,
 * because the engine is told about it at construction; the loop that reads the keyboard is built
 * after.
 *
 * <p><b>It no longer has to work out when a turn ended.</b> On the door this drives, ask returns
 * the outcome, so the loop is told directly. This prints what arrives while the model is working
 * and nothing else -- which is all a narration was ever good for, the ending having been the part
 * it could only guess at.
 */
final class ConsoleNarration implements NarrationListener {

  private volatile AgentId agentId;
  private final ConsoleIo io;
  private volatile boolean spoke;

  ConsoleNarration(AgentId agentId, ConsoleIo io) {
    this.agentId = agentId;
    this.io = io;
  }

  @Override
  public void on(Narrated narrated) {
    if (!agentId.equals(narrated.agentId())) {
      return;
    }
    switch (narrated.event()) {
      // Flushed per delta, which is what makes this actually stream: print() only reaches the
      // terminal when what it wrote contains a newline, so without this a paragraph arrives in
      // one lump at the end -- finished rather than being written, the whole difference a person
      // can see.
      case Narration.ContentDelta(String text) -> {
        spoke = true;
        io.write(text);
        io.flush();
      }
      // Says only that the turn produced one. The words are what ask() returns, and the REPL
      // prints them; a provider that streams has already shown them delta by delta.
      case Narration.Answered _ -> {
        /* the answer itself is the caller's, not the watcher's */
      }
      case Narration.ActionsRequested(_, var calls, _) ->
          calls.forEach(
              call ->
                  io.write(
                      System.lineSeparator()
                          + "  [calling "
                          + call.toolName().value()
                          + "]"
                          + System.lineSeparator()));
      case Narration.CallFinished(var callId, _) ->
          io.write("  [" + callId.value() + " answered]" + System.lineSeparator());
      case Narration.CallFailed(var callId, _, _, String message) ->
          io.write("  [" + callId.value() + " failed: " + message + "]" + System.lineSeparator());
      case Narration.CallDenied(var callId, _, String reason, _) ->
          io.write("  [" + callId.value() + " denied: " + reason + "]" + System.lineSeparator());
      case Narration.TurnFailed _,
          Narration.TurnStopped _,
          Narration.TurnRefused _,
          Narration.Terminated _ -> {
        // How it ended is the outcome's to report, and the loop has it.
      }
      // Thinking is shown as a marker, not as content: a model's reasoning is not its answer.
      case Narration.Thinking() -> io.write("  [thinking]" + System.lineSeparator());
      case Narration.TurnStarted _,
          Narration.InferenceRetried _,
          Narration.Commentary _,
          Narration.CallApproved _,
          Narration.ApprovalSought _,
          Narration.ApprovalDeferred _,
          Narration.CallDeferred _,
          Narration.ThinkingDelta _ -> {
        // Not something a person at a terminal needs told; the approver prompts for itself.
      }
    }
  }

  /** Listens for a different conversation from now on, as after {@code /clear}. */
  void follow(AgentId next) {
    this.agentId = next;
  }

  /** A new turn has nothing said in it yet. */
  void beginTurn() {
    spoke = false;
  }

  boolean spoke() {
    return spoke;
  }
}
