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
package org.jwcarman.nessy.agent.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.TurnOutcome;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;

/**
 * The CLI front end (front-ends spec §3): owns the terminal, drives {@link Agent#ask} at a prompt,
 * and answers §5a approvals through {@link #approver()}. Built only by {@link
 * Nessy.CliBuilder#build()} — the sugar door composing a harness (in-memory substrate, this
 * console's own approver, and {@code relay} as the harness's live narration channel) with a fresh
 * {@code Console}.
 *
 * <p><b>Resuming a parked turn</b> (the runner's "re-asks"): {@link #run()} answers a {@link
 * TurnOutcome.Parked} by handing the ticket to {@link #approver()}, then waits for the SAME turn to
 * settle — subscribing before deciding (the same race-avoidance order {@link Agent#ask} itself
 * uses), never by telling a fresh observation, since that would silently enqueue a second, unwanted
 * user turn behind the one still in flight. Detecting parking is off-channel (a {@code TurnEvent}
 * is never emitted for it), so this stays a package-private, public-{@link Agent}-API- only
 * approximation of {@link Agent#ask}'s own mechanics: a tool call that requires a SECOND, nested
 * approval while this wait is already in flight settles once that second request is separately
 * decided (through this same {@link #approver()}, e.g. via a later prompt), but this method has no
 * way to surface that second ticket itself — it can only keep waiting for the turn's eventual
 * {@code TurnEnded}. A single approval gate per turn, the common case, resolves cleanly.
 */
public final class Console implements AutoCloseable {

  private final Agent<String> agent;
  private final Harness<String> harness;
  private final RelayTurnObserver relay;
  private final BufferedReader in;
  private final PrintStream out;
  private final ExecutorService executor;
  private final boolean ownsExecutor;

  Console(
      Agent<String> agent,
      Harness<String> harness,
      RelayTurnObserver relay,
      InputStream in,
      PrintStream out,
      ExecutorService executor,
      boolean ownsExecutor) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
    this.relay = Objects.requireNonNull(relay, "relay must not be null");
    this.in =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(in, "in must not be null"), StandardCharsets.UTF_8));
    this.out = Objects.requireNonNull(out, "out must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.ownsExecutor = ownsExecutor;
  }

  /**
   * The §5a immediate-decision arm as a face (spec §3): renders the flattened {@link
   * ApprovalRequest} and answers by its id. A fresh, stateless handle every call — nothing to hold
   * onto between calls.
   */
  public Approver approver() {
    return new Approver();
  }

  /**
   * The runner (spec §3): reads a line, {@link Agent#ask}s, renders the outcome — {@code Replied}
   * prints the assistant's text, {@code Parked} routes to {@link #approver()} and waits for the
   * turn to settle (see the class javadoc), {@code Failed} says so honestly. Blank lines are
   * skipped; the loop ends at end of input.
   */
  public void run() {
    String line;
    while ((line = readLineOrNull()) != null) {
      if (line.isBlank()) {
        continue;
      }
      render(settle(agent.ask(line)));
    }
  }

  private TurnOutcome settle(TurnOutcome outcome) {
    TurnOutcome current = outcome;
    while (current instanceof TurnOutcome.Parked parked) {
      current = decideAndAwait(parked.ask());
    }
    return current;
  }

  /**
   * Subscribes before deciding — the same ordering {@link Agent#ask} uses to avoid a synchronous
   * resumption racing ahead of the wait that is meant to catch it — then hands {@code request} to
   * {@link #approver()} and blocks for the turn's next {@code TurnEnded} (or a further {@code
   * Parked}, handled by {@link #settle}'s own loop on the value this returns).
   */
  private TurnOutcome decideAndAwait(ApprovalRequest request) {
    CompletableFuture<TurnOutcome> outcome = new CompletableFuture<>();
    AtomicReference<String> lastAssistantText = new AtomicReference<>("");
    TurnObserver capture =
        event -> {
          switch (event) {
            case TurnEvent.AssistantSaid said -> lastAssistantText.set(joinedText(said.message()));
            case TurnEvent.TurnEnded ended ->
                outcome.complete(
                    ended.failed()
                        ? new TurnOutcome.Failed(ended.failureReason())
                        : new TurnOutcome.Replied(lastAssistantText.get()));
            case TurnEvent.TextDelta _ -> {}
            case TurnEvent.ThinkingDelta _ -> {}
            case TurnEvent.RedactedThinking _ -> {}
            case TurnEvent.ToolCallRequested _ -> {}
            case TurnEvent.ToolCallDecided _ -> {}
            case TurnEvent.ToolCallCompleted _ -> {}
            case TurnEvent.ToolCallProgressed _ -> {}
          }
        };
    try (Subscription subscription = agent.subscribe(capture)) {
      approver().decide(request);
      return outcome.join();
    }
  }

  private void render(TurnOutcome outcome) {
    switch (outcome) {
      case TurnOutcome.Replied replied -> out.println(replied.text());
      case TurnOutcome.Failed failed -> out.println("(turn failed: " + failed.reason() + ")");
      case TurnOutcome.Parked parked ->
          throw new IllegalStateException(
              "settle() must resolve every Parked outcome before render(): " + parked);
    }
  }

  private String readLineOrNull() {
    try {
      return in.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String joinedText(Message message) {
    StringBuilder joined = new StringBuilder();
    for (ContentBlock block : message.content()) {
      if (block instanceof TextBlock(String text)) {
        joined.append(text);
      }
    }
    return joined.toString();
  }

  /**
   * Package-visible so a test can drive {@link Agent#subscribe} directly to prove the fanout
   * composition (front-ends spec, Task 4's fix): {@code Console} itself never exposes this publicly
   * — the terminal is the only face application code gets.
   */
  Agent<String> agent() {
    return agent;
  }

  /**
   * Package-visible for the same reason as {@link #agent()}: lets a test install its own delegate
   * on the harness's global {@link TurnObserver} to prove it and an {@link Agent#subscribe}d
   * observer each see a given event exactly once (front-ends spec, Task 4's fix). {@code Console}
   * never sets a delegate on it itself — an unset relay drops every event, harmlessly, exactly as
   * {@link RelayTurnObserver}'s own javadoc promises.
   */
  RelayTurnObserver relay() {
    return relay;
  }

  /**
   * Shuts down this console's harness (its worker heartbeat), then closes the owned executor if any
   * — the reverse of build-time construction order, matching {@code Harness}'s own lifecycle
   * discipline (harness-first spec §4).
   */
  @Override
  public void close() {
    harness.shutdown();
    if (ownsExecutor) {
      executor.close();
    }
  }

  /**
   * The §5a immediate-decision arm (spec §3): renders the flattened {@link ApprovalRequest} ({@code
   * id}, {@code call}, {@code agentType}, {@code agentId}), reads y/n(+reason) from the console's
   * input, and answers through {@link Harness#approvals()} by {@link ApprovalRequest#id()}. A blank
   * denial reason is not sent as-is ({@link org.jwcarman.nessy.agent.ApprovalDesk#deny} refuses a
   * blank one loudly) — it is replaced with a fixed, honest default instead.
   */
  public final class Approver {

    private static final String DEFAULT_DENIAL_REASON = "denied at the console";

    private Approver() {}

    public void decide(ApprovalRequest request) {
      Objects.requireNonNull(request, "request must not be null");
      out.println(
          "approval requested — "
              + request.agentType()
              + "/"
              + request.agentId()
              + ": "
              + request.call().name()
              + " "
              + request.call().arguments());
      out.print("approve? [y/N] ");
      out.flush();
      String answer = readLineOrNull();
      if (isYes(answer)) {
        harness.approvals().approve(request.id());
        out.println("approved.");
      } else {
        out.print("reason: ");
        out.flush();
        String reason = readLineOrNull();
        String effectiveReason =
            (reason == null || reason.isBlank()) ? DEFAULT_DENIAL_REASON : reason;
        harness.approvals().deny(request.id(), effectiveReason);
        out.println("denied: " + effectiveReason);
      }
    }

    private boolean isYes(String answer) {
      if (answer == null) {
        return false;
      }
      String trimmed = answer.trim();
      return trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes");
    }
  }
}
