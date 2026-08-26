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
package org.jwcarman.nessy.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.StaleStateException;
import org.jwcarman.nessy.api.Identifiers;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * The shell: load–handle–save–dispatch with a retry (§3.4). No concurrency machinery — the store's
 * version CAS is the only lock (§3.2), and executors deliver on their own stacks (§4). Bound to one
 * scope at construction (§10.11): {@code harness} carries every id-free collaborator, {@code
 * binding} the thin, id-specific handles — instances are cheap, transient, and interchangeable
 * (§4.3).
 */
public final class DefaultAgent<O> implements Agent<O> {

  private final Harness<O> harness;
  private final Binding<O> binding;
  private final ModelCallExecutor model;
  private final ToolCallExecutor tools;

  DefaultAgent(Harness<O> harness, Binding<O> binding) {
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
    this.binding = Objects.requireNonNull(binding, "binding must not be null");
    this.model =
        Objects.requireNonNull(harness.modelExecutor(binding), "modelExecutor must not be null");
    this.tools =
        Objects.requireNonNull(harness.toolExecutor(binding), "toolExecutor must not be null");
  }

  /**
   * Every fold this shell commits goes out on the harness's one fact stream (agentic-o11y spec §3),
   * stamped with this scope's id — the same door {@link DeliveryWorker} publishes its durable folds
   * through, so a subscriber sees one account of the scope rather than two half-accounts.
   */
  private FactFanout facts() {
    return harness.facts();
  }

  @Override
  public void tell(O observation) {
    binding.backlog().add(observation);
    drive();
  }

  @Override
  public Subscription subscribe(TurnObserver observer) {
    return harness.subscribe(binding.id(), observer);
  }

  /**
   * The pattern (front-ends spec §1): subscribe a private capture, {@code tell}, block for the
   * turn's own {@link TurnOutcome}, close. Zero new event types — the resolution reads {@link
   * TurnEvent.AssistantSaid}/{@link TurnEvent.TurnEnded} exactly as {@link #subscribe} always
   * delivered them, since the fold retains no failure residue to read back any other way (a failed
   * turn folds to {@link AgentPhase.Idle} committing nothing).
   *
   * <p>Parking is the one outcome {@link TurnEvent} can never carry (its own javadoc: "Parking is
   * never narrated at all"), so it is detected off the fold itself: the {@code ApprovalDeferred}
   * that records the park completes this id's registered wait (see {@link
   * Harness#awaitApproval(AgentId)} and {@link Harness#parked(AgentId, TurnOutcome.Parked)}). A
   * fresh wait is registered for this id BEFORE {@code tell} ever dispatches anything, so a
   * synchronous park inside the very call that registers it can never race ahead of the
   * registration. Whichever resolves first — the turn's own {@code TurnEnded}, or the park —
   * decides the {@link TurnOutcome}; the other input, if it never fires, is retired in the {@code
   * finally} block so it cannot leak into a later, unrelated {@code ask} on the same id.
   */
  @Override
  public TurnOutcome ask(O observation) {
    Objects.requireNonNull(observation, "observation must not be null");
    AgentId id = binding.id();
    CompletableFuture<TurnOutcome> outcome = new CompletableFuture<>();
    TurnObserver capture = TurnOutcome.capturing(outcome);
    // awaitApproval throws if a previous ask on this id is still in flight (fix round 2, I2b) —
    // before subscribe ever runs, so nothing is left half-registered.
    CompletableFuture<TurnOutcome.Parked> approvalWait = harness.awaitApproval(id);
    approvalWait.thenAccept(outcome::complete);
    try (Subscription subscription = subscribe(capture)) {
      tell(observation);
      return outcome.join();
    } finally {
      harness.cancelApprovalWait(id, approvalWait);
    }
  }

  @Override
  public void drive() {
    Versioned<AgentPhase> state = binding.store().load();
    if (state.value() instanceof AgentPhase.Idle) {
      drain();
      return;
    }
    if (isStale(state)) {
      List<Effect> outstanding = state.value().outstanding();
      facts().reFired(binding.id(), outstanding);
      outstanding.forEach(effect -> dispatch(effect, state.value())); // §6.1 — the re-fire arm
    }
  }

  /**
   * The continuation door: executors are handed this method reference as their Sink at dispatch
   * (§4). Completions that lose the version race re-handle against fresh state until applied or
   * ignored (§3.4).
   *
   * <p>A fold that cannot commit narrates {@link HarnessObserver#applyFailed} and then
   * <b>rethrows</b>. Nothing outside now depends on that rethrow to stay honest — no door hands out
   * an id any more (deferral-by-callback spec §7) — but it stays: a caller that drove this fold
   * synchronously should learn that nothing was written. A DROPPED event is not a failure: this
   * returns normally, as it always has.
   *
   * <p><b>Only the commit is guarded.</b> {@link #commit} covers handle → remember → save; the
   * transition's effects are dispatched afterwards, by {@link #follow}, OUTSIDE the catch. That
   * separation is what keeps the narration honest when an executor runs inline ({@code
   * HarnessConfig.executor(Runnable::run)}): a dispatched effect can re-enter this method on the
   * same thread, and a failure in that NESTED fold is narrated once, by the nested frame, against
   * the event that actually failed. Were dispatch inside the guarded region, the outer frame would
   * narrate the same failure a second time against the wrong event before rethrowing. It still
   * propagates to whoever called the outer {@code deliver} — {@code ask()}/{@code tell()} with an
   * inline executor — but exactly one {@code applyFailed} describes it.
   */
  void deliver(AgentEvent event) {
    commit(event).ifPresent(this::follow);
  }

  /**
   * The guarded region: decide, remember, commit. Empty when the event was ignored — nothing was
   * written, so there is nothing to follow.
   */
  private Optional<AgentTransition> commit(AgentEvent event) {
    while (true) {
      try {
        return publish(event, folded(() -> applyOnce(binding.store().load(), event)));
      } catch (StaleStateException _) {
        // another writer advanced the scope — re-handle against what it left behind
        countStaleRetry();
      } catch (RuntimeException e) {
        facts().applyFailed(binding.id(), event, e); // narrate — then let the caller see it (§3)
        throw e;
      }
    }
  }

  /**
   * One fold attempt, inside the {@code nessy.fold} span (in-the-loop amendment §2): load, handle,
   * remember, CAS save, and nothing else — so the span's duration IS the store write plus the
   * reduce plus the remembrance, and so that a store recording its own observation nests under it.
   * A lost CAS escapes as it always did, which is what makes a retry a SECOND span rather than one
   * long one.
   */
  private Optional<AgentTransition> folded(Supplier<Optional<AgentTransition>> attempt) {
    return harness.observations().fold(binding.id(), harness.type(), attempt);
  }

  private Optional<AgentTransition> applyOnce(Versioned<AgentPhase> state, AgentEvent event) {
    AgentTransition t = state.value().handle(event); // decide before committing
    if (t.isDropped()) {
      return Optional.empty();
    }
    remember(state.value(), event, t); // remember before commit (remembrance spec §1 law 1)
    binding.store().save(new Versioned<>(t.next(), state.version()));
    return Optional.of(t);
  }

  /**
   * The fold's OUTPUT on the harness's one fact stream (agentic-o11y spec §3), published once
   * {@link #folded}'s span has closed. Deliberately outside that span: the stream is where the
   * {@code invoke_agent} segment opens, and a segment created inside a fold's scope would become
   * the child of a fold that stops immediately — inverting the rule that the segment is the parent
   * of everything (in-the-loop amendment §2).
   */
  private Optional<AgentTransition> publish(AgentEvent event, Optional<AgentTransition> committed) {
    if (committed.isEmpty()) {
      facts().ignored(binding.id(), event);
      return committed;
    }
    facts().applied(binding.id(), event, committed.get());
    // The fold IS the park (approval-lifecycle spec §1.3): by the time this commits, the phase
    // names the ask, so whoever is waiting on this turn can be told about it.
    if (event instanceof AgentEvent.ApprovalDeferred(var _, var approval, var request, var _)) {
      harness.parked(binding.id(), new TurnOutcome.Parked(approval, request));
    }
    return committed;
  }

  /**
   * What a committed transition sets in motion, run OUTSIDE {@link #commit}'s catch — see {@link
   * #deliver}. With an inline executor these effects re-enter {@code deliver} on this very thread,
   * so anything that fails in there is already narrated by its own frame.
   */
  private void follow(AgentTransition t) {
    t.effects().forEach(effect -> dispatch(effect, t.next()));
    if (t.next() instanceof AgentPhase.Idle && harness.drainOnIdle()) {
      drive(); // §3.1 — the drain-on-idle wiring's own drive executor
    }
  }

  /**
   * The three fold moments (remembrance spec §2), mapped from the event {@code t} folded FROM
   * {@code priorPhase}: an observation remembers a fresh {@link Remembrance.UserMessage} (this
   * shell layer mints its own opaque key — nothing upstream hands it a stable one, unlike the
   * durable layer's {@code ModelResponseId}/execution {@code ComputationId}); a model turn that
   * ends the turn outright (no pending tool calls) remembers a {@link Remembrance.AssistantMessage}
   * keyed by its own committed response id right here — a model turn that OPENS a fan-out instead
   * defers its {@link Remembrance.AssistantMessage} to {@link ToolFoldRemembrance}, which remembers
   * it alongside whichever tool call completes the batch (see that class's own javadoc, and {@link
   * Remembrance.AssistantMessage}'s, for the arrival-order story {@link
   * org.jwcarman.nessy.spi.Memory#recall()} owes); a tool completion always folds through {@link
   * ToolFoldRemembrance}, the same mapping {@link DeliveryWorker} uses for the durable arm of the
   * very same fold moment.
   */
  private void remember(AgentPhase priorPhase, AgentEvent event, AgentTransition t) {
    Memory memory = binding.memory();
    switch (event) {
      case AgentEvent.Observed _ ->
          memory.remember(new Remembrance.UserMessage(Identifiers.next(), t.commit().getFirst()));
      case AgentEvent.ModelFinished(ModelOutcome.Responded(var _, var calls, var responseId))
          when calls.isEmpty() ->
          memory.remember(
              new Remembrance.AssistantMessage(responseId.value(), t.commit().getFirst()));
      case AgentEvent.ModelFinished _ -> {
        // a deferred assistant turn (tool calls pending — the message rides AwaitingTools until
        // every call answers) or a Failed outcome (AgentPhase.AwaitingModel#handle discards it):
        // nothing committed, nothing to remember yet.
      }
      case AgentEvent.ToolFinished(var call, var _, var outcome) ->
          ToolFoldRemembrance.remember(
              memory, harness.type(), binding.id(), priorPhase, call, outcome, t);
      case AgentEvent.ApprovalAnswered(var call, var _, Approval.Denied(var reason, var _)) ->
          ToolFoldRemembrance.rememberDenial(
              memory, harness.type(), binding.id(), priorPhase, call, reason, t);
      case AgentEvent.ApprovalDeferralRequested _,
          AgentEvent.ApprovalDeferred _,
          AgentEvent.ApprovalAnswered _,
          AgentEvent.ToolCallDeferralRequested _,
          AgentEvent.ToolCallDeferred _ -> {
        // an approval, a deferral asked for, a park: no message committed, nothing to remember
      }
    }
  }

  private void drain() {
    while (true) {
      Versioned<AgentPhase> state = binding.store().load();
      if (!(state.value() instanceof AgentPhase.Idle)) {
        return;
      }
      Optional<O> next = binding.backlog().poll();
      if (next.isEmpty()) {
        return;
      }
      drainOne(state, next.get());
    }
  }

  /** One backlog observation's whole drain attempt: render, apply, or discard (§3.7, §3.3). */
  private void drainOne(Versioned<AgentPhase> state, O observation) {
    List<ContentBlock> content;
    try {
      content = harness.renderer().render(observation);
    } catch (RuntimeException e) {
      facts().renderFailed(binding.id(), observation, e); // discard; stay idle; keep draining
      return;
    }
    if (content.isEmpty()) {
      return; // an empty render is a decline — skip, keep draining (§3.7)
    }
    AgentEvent observed = new AgentEvent.Observed(content);
    Optional<AgentTransition> committed;
    try {
      committed = publish(observed, folded(() -> applyOnce(state, observed)));
    } catch (StaleStateException _) {
      countStaleRetry();
      binding.backlog().add(observation); // lost race → back to the backlog (§3.3)
      facts().observationRequeued(binding.id(), observation);
      return;
    } catch (RuntimeException e) {
      // A genuine failure inside the COMMIT (e.g. a throwing Memory#remember — Memory's own law 1,
      // the non-durable shell arm): the observation goes back to the backlog exactly as the
      // stale-state race above does, so it is not lost. Unlike a stale race — an ordinary,
      // expected condition this shell absorbs and keeps draining past — this is NOT swallowed:
      // silently continuing to drain would hot-loop a permanently broken Memory forever. The
      // exception surfaces to whoever called tell()/drive(), which decides whether to retry.
      binding.backlog().add(observation);
      throw e;
    }
    // Outside the catch, for the same reason deliver() dispatches outside its own (see there): with
    // an inline executor these effects re-enter deliver on this thread. Requeuing the observation
    // for a failure out HERE would be wrong twice over — it is already committed, so a redrive
    // would double-apply it, and the nested frame has already narrated the real failure.
    committed.ifPresent(this::follow);
  }

  /**
   * One stale-retry counted. Safe to call from inside the retry loop with no guard of its own (fix
   * round 1): {@link Observations#staleRetry} contains whatever a broken {@code ObservationHandler}
   * throws and never propagates, which is the property this loop needs — an escaping exception here
   * would abort the very convergence the loop exists for.
   */
  private void countStaleRetry() {
    harness.observations().staleRetry(binding.id(), harness.type());
  }

  private boolean isStale(Versioned<AgentPhase> state) {
    return harness.stalenessPolicy().isStale(state.value(), binding.store().lastSaved());
  }

  /**
   * {@code phase} is the committed state a call effect is dispatched alongside — always {@link
   * AgentPhase.AwaitingTools}, the only phase that ever carries one (§2.2) — and is where the
   * call's {@link ModelResponseId} is read from (durable-deliveries spec §2): minted once, in the
   * model-call executor, never re-derived here.
   */
  private void dispatch(Effect effect, AgentPhase phase) {
    switch (effect) {
      case Effect.CallModel _ -> model.callModel(this::deliver);
      case Effect.SeekApproval(var call) ->
          tools.seekApproval(call, responseIdOf(phase), this::deliver);
      case Effect.RunTool(var call) -> tools.runTool(call, responseIdOf(phase), this::deliver);
      case Effect.DeferApproval(var call, var request, var callback, var term) ->
          tools.deferApproval(call, request, callback, term, responseIdOf(phase), this::deliver);
      case Effect.DeferToolCall(var call, var callback, var term) ->
          tools.deferToolCall(call, callback, term, responseIdOf(phase), this::deliver);
    }
  }

  private static ModelResponseId responseIdOf(AgentPhase phase) {
    if (phase instanceof AgentPhase.AwaitingTools awaiting) {
      return awaiting.responseId();
    }
    throw new IllegalStateException("a call effect was dispatched outside AwaitingTools: " + phase);
  }
}
