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
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.TurnOutcome;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;

/**
 * The CLI front end (front-ends spec §3): owns the terminal, drives {@link Agent#ask} at a prompt,
 * and answers §5a approvals through {@link #approver()}. Built only by {@link
 * Nessy.CliBuilder#build()} — the sugar door composing a harness (in-memory substrate, this
 * console's own approver, and {@code relay} as the harness's live narration channel) with a fresh
 * {@code Console}, which installs itself as {@code relay}'s one delegate at construction — every
 * id's {@code TextDelta} streams live to the console's output, flushed per delta, the instant it
 * narrates, since {@code relay} is still the harness's global observer underneath.
 *
 * <p><b>Resuming a parked turn</b> (the runner's "re-asks"): {@link #run()} answers a {@link
 * TurnOutcome.Parked} by handing the ticket to {@link #approver()}, then waits for the SAME turn to
 * settle — subscribing before deciding (the same race-avoidance order {@link Agent#ask} itself
 * uses), never by telling a fresh observation, since that would silently enqueue a second, unwanted
 * user turn behind the one still in flight. Detecting parking is off-channel (a {@code TurnEvent}
 * is never emitted for it), so this stays a package-private, public-{@link Agent}-API-only
 * approximation of {@link Agent#ask}'s own mechanics — with an honest, uncorrected gap: {@link
 * #run()}'s loop is single-threaded, blocked inside {@link #decideAndAwait} waiting on the turn's
 * {@code TurnEnded} the instant a decision is made. If that SAME turn parks a SECOND time before
 * settling, nothing here is reading the console's input to answer it — this call has no way to
 * surface the second ticket, and {@link #run()} hangs on it UNRECOVERABLY (no timeout, matching
 * {@link Agent#ask}'s own honest blocking contract). The only way out is a door other than this
 * console's own stdin: decide the second ticket directly through {@link Harness#approvals()} — from
 * another thread, another process sharing the same substrate, or a debugger — which lets the turn
 * finish and this call return. A single approval gate per turn, the overwhelmingly common case,
 * resolves cleanly either way.
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
    // The spec §3 console observer (fix round 2, M9): installs itself as relay's one delegate, so
    // every id's TextDelta streams live to `out`, flushed per delta, the instant it narrates —
    // exactly-once via the SAME relay/global path the fanout composition already guarantees (relay
    // is this console's harness's configured turnObserver, composed as fanout's one global
    // subscriber; see Nessy.CliBuilder#build()). No other event type prints here — AssistantSaid's
    // full, settled text and TurnEnded's failure are `render`'s job alone, off the returned
    // TurnOutcome, so the two never race or duplicate.
    relay.set(this::narrateLive);
  }

  private void narrateLive(TurnEvent event) {
    if (event instanceof TurnEvent.TextDelta delta) {
      out.print(delta.text());
      out.flush();
    }
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

  /**
   * {@link #decideAndAwait} can only ever settle on {@code Replied} or {@code Failed} — its capture
   * completes {@code outcome} on {@code TurnEnded} alone, so a {@code Parked} return value is not
   * expressible here (unlike {@link Agent#ask}, which has an off-channel park-detection seam this
   * package-private, public-API-only approximation does not — see the class javadoc). No loop: one
   * decision, one wait.
   */
  private TurnOutcome settle(TurnOutcome outcome) {
    return outcome instanceof TurnOutcome.Parked parked ? decideAndAwait(parked.ask()) : outcome;
  }

  /**
   * Subscribes before deciding — the same ordering {@link Agent#ask} uses to avoid a synchronous
   * resumption racing ahead of the wait that is meant to catch it — then hands {@code request} to
   * {@link #approver()} and blocks for the turn's next {@code TurnEnded}. See the class javadoc for
   * what happens if the SAME turn parks again before that arrives.
   */
  private TurnOutcome decideAndAwait(ApprovalRequest request) {
    CompletableFuture<TurnOutcome> outcome = new CompletableFuture<>();
    TurnObserver capture = TurnOutcome.capturing(outcome);
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
