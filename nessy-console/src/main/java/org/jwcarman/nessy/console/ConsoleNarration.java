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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * What the REPL prints while the agent works, and how it knows a turn is over.
 *
 * <p>A {@link AgentEventListener} is engine-wide -- one sink hears every agent -- so this filters
 * to the one agent the terminal is talking to and ignores the rest. It is built before the engine,
 * because the engine is told about it at construction; the loop that reads the keyboard is built
 * after.
 *
 * <p>The end of a turn is signalled through a one-slot queue with {@code offer}, never {@code put}:
 * if nobody is waiting the notice is worth dropping, and blocking an engine thread on a REPL that
 * moved on never is.
 */
final class ConsoleNarration implements AgentEventListener {

  /** How a turn ended, as far as a terminal needs to know. */
  enum Ending {
    ANSWERED,
    FAILED,
    REFUSED,
    TERMINATED
  }

  private final AgentId agentId;
  private final ConsoleIo io;
  private final BlockingQueue<Ending> finished = new ArrayBlockingQueue<>(1);
  private volatile boolean spoke;

  ConsoleNarration(AgentId agentId, ConsoleIo io) {
    this.agentId = agentId;
    this.io = io;
  }

  @Override
  public void on(AgentType agentType, AgentId who, AgentEvent event) {
    if (!agentId.equals(who)) {
      return;
    }
    switch (event) {
      // Flushed per delta, which is what makes this actually stream: print() only reaches the
      // terminal when what it wrote contains a newline, so without this a paragraph arrives in
      // one lump at the end -- finished rather than being written, the whole difference a person
      // can see.
      case AgentEvent.ContentDelta(String text) -> {
        spoke = true;
        io.write(text);
        io.flush();
      }
      // A provider that does not stream says the whole thing at once; one that does has already
      // said it delta by delta, and saying it again would print the answer twice.
      case AgentEvent.Answered(String text) -> {
        if (!spoke && !text.isBlank()) {
          spoke = true;
          io.write(text);
          io.flush();
        }
        finished.offer(Ending.ANSWERED);
      }
      case AgentEvent.ActionsRequested(var toolNames) ->
          toolNames.forEach(
              name ->
                  io.write(
                      System.lineSeparator()
                          + "  [calling "
                          + name.value()
                          + "]"
                          + System.lineSeparator()));
      case AgentEvent.CallFinished(var callId) ->
          io.write("  [" + callId.value() + " answered]" + System.lineSeparator());
      case AgentEvent.CallFailed(var callId, String message) ->
          io.write("  [" + callId.value() + " failed: " + message + "]" + System.lineSeparator());
      case AgentEvent.CallDenied(var callId, String reason) ->
          io.write("  [" + callId.value() + " denied: " + reason + "]" + System.lineSeparator());
      case AgentEvent.TurnFailed() -> finished.offer(Ending.FAILED);
      case AgentEvent.TurnRefused() -> finished.offer(Ending.REFUSED);
      case AgentEvent.Terminated() -> finished.offer(Ending.TERMINATED);
      // Thinking is shown as a marker, not as content: a model's reasoning is not its answer.
      case AgentEvent.Thinking() -> io.write("  [thinking]" + System.lineSeparator());
      case AgentEvent.TurnStarted _,
          AgentEvent.TurnEnded _,
          AgentEvent.Commentary _,
          AgentEvent.CallApproved _,
          AgentEvent.ApprovalSought _,
          AgentEvent.ApprovalDeferred _,
          AgentEvent.CallDeferred _,
          AgentEvent.ThinkingDelta _ -> {
        // Not something a person at a terminal needs told; the approver prompts for itself.
      }
    }
  }

  /** Forgets anything left over from a turn nobody waited for, so it cannot end THIS one. */
  void beginTurn() {
    finished.clear();
    spoke = false;
  }

  boolean spoke() {
    return spoke;
  }

  Optional<Ending> awaitEnding(Duration patience) throws InterruptedException {
    return Optional.ofNullable(finished.poll(patience.toMillis(), TimeUnit.MILLISECONDS));
  }
}
