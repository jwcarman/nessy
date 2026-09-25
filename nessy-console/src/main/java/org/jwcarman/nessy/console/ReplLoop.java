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
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Outcome;

/**
 * Reads a line, hands it to the agent, prints what came back, prompts again.
 *
 * <p><b>There is nothing to wait for.</b> This used to hand the line over, then block on a
 * narration queue for five minutes and, on timing out, admit it could not tell whether the model
 * was still working or the news had simply never arrived. The door it drives now returns the
 * outcome, so both of those cases are gone: what happened is the return value.
 */
final class ReplLoop {

  private final DirectHarness<String> harness;
  private final AgentId agentId;
  private final ReplConfig config;
  private final ConsoleIo io;
  private final ConsoleNarration narration;

  ReplLoop(
      DirectHarness<String> harness,
      AgentId agentId,
      ReplConfig config,
      ConsoleIo io,
      ConsoleNarration narration) {
    this.harness = harness;
    this.agentId = agentId;
    this.config = config;
    this.io = io;
    this.narration = narration;
  }

  void run() {
    if (!config.banner().isEmpty()) {
      io.write(config.banner() + System.lineSeparator());
    }
    while (true) {
      io.write(System.lineSeparator() + config.prompt());
      io.flush();
      String line = io.readLine();
      if (line == null || config.isExit(line)) {
        break;
      }
      if (!line.isBlank()) {
        narration.beginTurn();
        report(harness.ask(agentId, line));
        io.flush();
      }
    }
    if (!config.farewell().isEmpty()) {
      io.write(System.lineSeparator() + config.farewell() + System.lineSeparator());
      io.flush();
    }
  }

  private void report(Outcome<String> outcome) {
    io.write(System.lineSeparator());
    switch (outcome) {
      case Outcome.Answered<String> _ -> reportAnswer();
      case Outcome.Refused<String>(String category) ->
          note("the model refused to answer: " + category);
      case Outcome.Failed<String>(String reason) -> note("the turn failed: " + reason);
      // Only reachable with a lock somebody else holds -- another terminal, or another machine
      // on the same agent. Worth saying plainly rather than looking like a failure.
      case Outcome.Busy<String> _ -> note("that agent is busy with another turn; try again");
    }
  }

  /** Nothing to add when the model spoke: the answer IS the report. */
  private void reportAnswer() {
    if (!narration.spoke()) {
      note("the model ended the turn without saying anything");
    }
  }

  private void note(String what) {
    io.write("  [" + what + "]" + System.lineSeparator());
  }
}
