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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentSubscription;
import org.jwcarman.nessy.api.Harness;

/**
 * Read a line, post it, watch the answer arrive, prompt again.
 *
 * <p><b>The loop looks synchronous and the engine is not.</b> {@link Harness#observe} returns the
 * moment the line is durably the agent's problem; the answer arrives later, on other threads, as
 * events. What makes a REPL out of that is the one thing here that waits — after posting a line it
 * blocks until it sees {@code TurnEnded}, so a person is never asked to type over a reply that is
 * still being written. An unattended application simply would not wait.
 *
 * <p>Separate from {@link Repl} so it can be tested: everything here needs is a harness, an id, and
 * somewhere to read and write, none of which has to be real.
 */
final class ReplLoop {

  /** How long to wait for a turn before telling the person it is still going. */
  private static final Duration PATIENCE = Duration.ofMinutes(5);

  private final Harness<String> harness;
  private final AgentId agentId;
  private final ReplConfig config;
  private final ConsoleIo io;

  /**
   * One slot, holding "the turn you were waiting for is over". A queue rather than a latch because
   * the loop waits again on the next line, and a latch does not reset.
   */
  private final BlockingQueue<AgentEvent.TurnEnded> finished = new ArrayBlockingQueue<>(1);

  ReplLoop(Harness<String> harness, AgentId agentId, ReplConfig config, ConsoleIo io) {
    this.harness = harness;
    this.agentId = agentId;
    this.config = config;
    this.io = io;
  }

  void run() {
    // Closed on the way out: an unclosed subscription leaves a routing entry behind, and the
    // engine going on narrating into a REPL that has left is how a clean exit turns into a
    // warning about dropped messages.
    try (AgentSubscription listening = harness.subscribe(agentId, printing())) {
      converse();
    }
  }

  private void converse() {
    if (!config.banner().isEmpty()) {
      io.write(config.banner() + System.lineSeparator());
    }
    while (true) {
      io.write(System.lineSeparator() + config.prompt());
      io.flush();
      String line = io.readLine();
      if (line == null || config.exitWords().contains(line.strip())) {
        break;
      }
      if (line.isBlank()) {
        continue;
      }
      // Anything left over from a turn nobody waited for must not end THIS one instantly.
      finished.clear();
      harness.observe(agentId, line);
      awaitTurn();
    }
    if (!config.farewell().isEmpty()) {
      io.write(System.lineSeparator() + config.farewell() + System.lineSeparator());
      io.flush();
    }
  }

  /**
   * What the person sees. Deltas print as they arrive, so an answer appears at the speed the model
   * writes it; tool calls announce themselves, because a pause with no explanation looks like a
   * hang.
   */
  private AgentSubscriber printing() {
    return AgentSubscriber.of(
        events ->
            events
                // Flushed per delta, which is what makes this actually stream. print() only
                // reaches the terminal when what it wrote contains a newline, so without this a
                // paragraph arrives in one lump at the end — the answer appears finished rather
                // than being written, which is the whole difference a person can see.
                .onTextDelta(delta -> writeNow(delta.text()))
                .onToolCallRequested(
                    call ->
                        io.write(
                            System.lineSeparator()
                                + "  [calling "
                                + call.toolName()
                                + "]"
                                + System.lineSeparator()))
                .onToolCallCompleted(
                    call ->
                        io.write("  [" + call.toolName() + " answered]" + System.lineSeparator()))
                // offer(), not put(): if nobody is waiting the notice is worth dropping, and
                // blocking an engine thread on a REPL that moved on never is.
                .onTurnEnded(finished::offer));
  }

  /** Written and made visible immediately: a REPL's output is watched, not collected. */
  private void writeNow(String text) {
    io.write(text);
    io.flush();
  }

  private void awaitTurn() {
    try {
      AgentEvent.TurnEnded ended = finished.poll(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
      if (ended == null) {
        io.write(
            System.lineSeparator()
                + "  [no answer yet; the agent may still be working]"
                + System.lineSeparator());
      } else {
        io.write(System.lineSeparator());
      }
      io.flush();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
