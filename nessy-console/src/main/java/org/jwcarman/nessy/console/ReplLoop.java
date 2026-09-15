package org.jwcarman.nessy.console;

import java.time.Duration;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Harness;

/** Reads a line, hands it to the agent, waits for the turn to end, prompts again. */
final class ReplLoop {

  private static final Duration PATIENCE = Duration.ofMinutes(5);

  private final Harness<String> harness;
  private final AgentId agentId;
  private final ReplConfig config;
  private final ConsoleIo io;
  private final ConsoleNarration narration;

  ReplLoop(
      Harness<String> harness,
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
        harness.observe(agentId, line);
        awaitTurn();
      }
    }
    if (!config.farewell().isEmpty()) {
      io.write(System.lineSeparator() + config.farewell() + System.lineSeparator());
      io.flush();
    }
  }

  private void awaitTurn() {
    try {
      Optional<ConsoleNarration.Ending> ended = narration.awaitEnding(PATIENCE);
      if (ended.isEmpty()) {
        // Not "still working": the other possibility is that the turn finished and the news never
        // arrived, which is what a lost narration looks like from here. Naming both is the
        // difference between looking at the model and looking at the plumbing.
        io.write(
            System.lineSeparator()
                + "  [no answer after "
                + PATIENCE.toMinutes()
                + "m -- still working, or the turn ended without reaching this listener]"
                + System.lineSeparator());
      } else {
        io.write(System.lineSeparator());
        report(ended.get());
      }
      io.flush();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private void report(ConsoleNarration.Ending ending) {
    switch (ending) {
      case REFUSED -> note("the model refused to answer");
      case FAILED -> note("the turn failed; the model could not be reached or did not finish");
      case TERMINATED -> note("the agent was terminated");
      case ANSWERED -> {
        if (!narration.spoke()) {
          note("the model ended the turn without saying anything");
        }
        // Otherwise it said its piece: the answer IS the report.
      }
    }
  }

  private void note(String what) {
    io.write("  [" + what + "]" + System.lineSeparator());
  }
}
