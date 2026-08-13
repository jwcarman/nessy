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
package org.jwcarman.nessy.internal;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.AgendaItem;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Effect;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.conversation.Step;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.StaleStateException;
import org.jwcarman.nessy.spi.execute.EffectExecutors;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * The invariant loop — the fold→perform cycle, written once, owned by the core. Engines do not
 * exist anymore; this is the machinery every assembly shares, varying only in the executors,
 * memory, store, and policy handed to it.
 *
 * <p>The unified drive (design 2026-08-12): every entry — a tell, a resolution — appends to the
 * conversation's durable agenda regardless of status; nothing is ever refused. Exactly one verb,
 * {@link #drive}, takes up that agenda and the conversation's status pointer and does whatever is
 * next — re-entrant from any status, retried on a fenced save's contention, park-aware. The cycle
 * per fact is unchanged: fold it; consult the termination policy after every fold — a law, not a
 * list of check sites; tell {@link Memory} the fold's message births; emit the fact on the system
 * channel; persist; then perform the emitted effects, each yielding the next fact. A halt discards
 * unperformed effects — intents, not obligations — and applies the closure transition {@code
 * halted(reason)}; a park applies {@code parked(call, token)} the same fold-free, loop-applied way.
 *
 * <p>Durability: the most recent state is saved on every exit path, including exceptions — the
 * progress-holder contract, now shared by every closure transition this loop applies, not just the
 * terminal one.
 */
public final class ConversationLoop {

  private static final int MAX_DRIVE_ATTEMPTS = 5;

  private final EffectExecutors executors;
  private final Memory memory;
  private final TerminationPolicy termination;
  private final ConversationStore store;
  private final EventEmitter emitter;
  private final ObservationRegistry observations;

  /** What one performed effect yielded: a settled fact to fold, or a park to apply. */
  private sealed interface PerformOutcome {

    record Settled(ConversationEvent fact) implements PerformOutcome {}

    record Parked(ToolCall call, ParkToken token) implements PerformOutcome {}
  }

  public ConversationLoop(
      EffectExecutors executors,
      Memory memory,
      TerminationPolicy termination,
      ConversationStore store,
      EventEmitter emitter,
      ObservationRegistry observations) {
    this.executors = Objects.requireNonNull(executors, "executors must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.termination = Objects.requireNonNull(termination, "termination must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  /** {@code tell}: appends nothing but a note, then drives. The fact itself is minted at drain. */
  public RunOutcome run(
      ConversationId id, ConversationEvent.AgentTold input, TurnObserver observer) {
    Objects.requireNonNull(observer, "observer must not be null");
    store.appendAgenda(id, AgendaItem.told(input.content()));
    return drive(id, observer);
  }

  /**
   * Appends nothing; drives the conversation from wherever its status points. Re-entrant from any
   * status: idle with queued mail, a crashed in-flight turn, a parked wait past its resolution.
   * Retried up to {@link #MAX_DRIVE_ATTEMPTS} times on {@link StaleStateException} — another driver
   * moved the fenced base out from under this attempt — before letting the exception surface.
   */
  public RunOutcome drive(ConversationId id, TurnObserver observer) {
    Objects.requireNonNull(observer, "observer must not be null");
    Observation observation = EngineObservations.run(observations, id);
    try (var _ = observation.openScope()) {
      return driveWithRetries(id, observer);
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }

  /**
   * The retry loop of {@link #drive}, extracted so it is not a nested try block: {@link #driveOnce}
   * up to {@link #MAX_DRIVE_ATTEMPTS} times, retrying on {@link StaleStateException} until another
   * driver's win must be let through.
   */
  private RunOutcome driveWithRetries(ConversationId id, TurnObserver observer) {
    for (int attempt = 1; ; attempt++) {
      try {
        return driveOnce(id, observer);
      } catch (StaleStateException e) {
        if (attempt >= MAX_DRIVE_ATTEMPTS) {
          throw e; // somebody keeps winning; the caller retries or reads
        }
        // another driver moved the base — reload and re-enter
      }
    }
  }

  /**
   * One drive attempt against one load: drains queued notes, routes any resolutions the parked
   * agenda is waiting on, then continues by whatever the status pointer says. Saving is not one act
   * per attempt: every fold along the way ({@link #fold}) persists as soon as it lands, draining
   * whatever agenda ids have accumulated so far — that per-entry drain is the thing that is exactly
   * once (design §4: an entry joins {@code drained} only alongside the save that actually consumes
   * it). What this method's own exit paths add on top is at most one further tail save, for state
   * this attempt reached without a fold of its own to carry it — a pointer pass that found nothing
   * left to do, or an exception escaping before landing a save.
   */
  private RunOutcome driveOnce(ConversationId id, TurnObserver observer) {
    ConversationStore.Loaded loaded =
        store
            .load(id)
            .orElseGet(
                () ->
                    new ConversationStore.Loaded(ConversationState.newConversation(id), List.of()));
    AtomicReference<ConversationState> progress = new AtomicReference<>(loaded.state());
    List<String> drained = new ArrayList<>();
    boolean settled = false;
    try {
      // 1. Notes: fold every Told entry, in order (facts minted here, one per entry). The entry's
      //    own id joins `drained` BEFORE its fold, transactional with that fold's own save (design
      //    §4): a note is never left on the agenda once the fold that consumed it has landed.
      for (AgendaItem entry : loaded.agenda()) {
        if (entry instanceof AgendaItem.Told(String entryId, List<ContentBlock> content)) {
          drained.add(entryId);
          fold(progress, new ConversationEvent.AgentTold(id, content), drained);
        }
      }

      // 2. Resolutions: route every Resolved entry whose token still names a live park —
      //    regardless of the conversation's current status. A resolution can legitimately arrive
      //    while a fan-out sibling is still unsettled (crash mid-fan-out: EXECUTING_TOOL with
      //    parkedCalls non-empty, not PARKED), and gating this pass on status == PARKED alone
      //    stranded that resolution — consumed by resume, appended to the agenda, but never
      //    routed, wedging the conversation. Routing by park membership instead fixes both
      //    directions: a resolution whose token IS a live park routes here no matter the status; a
      //    resolution whose token is NOT a live park drains quietly no matter the status too (a
      //    stale entry left behind by a settled call no longer lingers outside PARKED waiting for
      //    a status it will never see again). Unlike a note's fold, resuming a call can throw
      //    before ever reaching a fold (the re-park guard, below) — so the entry's id joins
      //    `drained` only once resumeParkedCall and its fold have both succeeded; a throw between
      //    them must leave the entry on the agenda for a future retry to find, not destroy the
      //    only copy of the resolution that arrived.
      for (AgendaItem entry : loaded.agenda()) {
        if (entry
            instanceof
            AgendaItem.Resolved(String entryId, ParkToken token, ToolResolution resolution)) {
          Optional<ParkedCall> park =
              progress.get().parkedCalls().stream()
                  .filter(candidate -> candidate.token().equals(token))
                  .findFirst();
          if (park.isEmpty()) {
            drained.add(entryId); // stale resolution: token's call already settled
            continue;
          }
          ConversationEvent fact =
              resumeParkedCall(park.get(), resolution, progress.get(), observer);
          FoldOutcome folded = fold(progress, fact, drained);
          drained.add(entryId);
          runCycle(progress, new ArrayDeque<>(folded.effects()), drained, observer);
        }
      }

      // 3. The continuation pointer: do what status says until quiescent or parked.
      continueByStatus(progress, drained, observer);

      if (!drained.isEmpty()) {
        save(progress, drained);
      }
      settled = true;
      return outcomeOf(progress.get());
    } finally {
      if (!settled) {
        try {
          save(progress, drained);
        } catch (StaleStateException _) {
          // The winning driver owns the base now: whatever this attempt was trying to persist is
          // superseded, and the exception (or the original one already propagating) is the true
          // signal — a redundant stale save here must never mask it.
        }
      }
    }
  }

  private static RunOutcome outcomeOf(ConversationState state) {
    if (state.status() == ConversationStatus.PARKED) {
      return new RunOutcome.Parked(state, state.parkedCalls().getFirst().token());
    }
    return new RunOutcome.Completed(state);
  }

  /**
   * Advances {@code progress} by whatever its current status calls for, re-entrant from a crash at
   * any point in a turn: quiescent with unread notes opens one; a crashed or continued model call
   * is re-performed; crashed tool debt is re-performed (at-least-once — parked calls are not among
   * it). Every other status (quiescent with nothing queued, or parked) is left exactly as found.
   */
  private void continueByStatus(
      AtomicReference<ConversationState> progress, List<String> drained, TurnObserver observer) {
    ConversationState state = progress.get();
    if (state.isQuiescent() && !state.told().isEmpty()) {
      Step opened = state.openTurn();
      progress.set(opened.state());
      remember(opened.state().id(), opened.remember());
      save(progress, drained);
      runCycle(progress, new ArrayDeque<>(opened.effects()), drained, observer);
    } else if (state.status() == ConversationStatus.AWAITING_MODEL) {
      runCycle(progress, new ArrayDeque<>(List.of(Effect.callModel())), drained, observer);
    } else if (state.status() == ConversationStatus.EXECUTING_TOOL) {
      Deque<Effect> queue = new ArrayDeque<>();
      for (ToolCall call : state.pendingCalls()) {
        queue.addLast(new Effect.ExecuteTool(call));
      }
      runCycle(progress, queue, drained, observer);
    }
  }

  /** One fold's outcome for a cycle: the effects it yields, and whether it halted the turn. */
  private record FoldOutcome(List<Effect> effects, boolean halted) {}

  /**
   * Drains an effect queue to empty: perform, then either fold the settled fact's own effects onto
   * the same queue, or apply the park closure and move on to whatever else was queued (park
   * physics: a sibling call queued beside a parking one still gets performed). A halt abandons
   * whatever else is still queued — intents, not obligations — and returns immediately rather than
   * performing it. Returns once the queue empties — quiescent, halted, or parked with nothing left
   * to run.
   */
  private void runCycle(
      AtomicReference<ConversationState> progress,
      Deque<Effect> queue,
      List<String> drained,
      TurnObserver observer) {
    while (!queue.isEmpty()) {
      Effect effect = queue.pollFirst();
      PerformOutcome outcome = perform(effect, progress.get(), observer);
      switch (outcome) {
        case PerformOutcome.Settled(ConversationEvent fact) -> {
          FoldOutcome folded = fold(progress, fact, drained);
          if (folded.halted()) {
            return;
          }
          folded.effects().forEach(queue::addLast);
        }
        case PerformOutcome.Parked(ToolCall call, ParkToken token) ->
            applyParked(progress, call, token, drained);
      }
    }
  }

  /**
   * The per-fact ordering law, unchanged: fold, consult the termination policy, remember the fold's
   * births, emit the fact, save (draining {@code drained}, which is cleared after). A halt discards
   * the fold's own effects and applies {@code halted(reason)} in the same beat, remembering both
   * the fold's own birth and the closure's abandoned-work flush before saving.
   */
  private FoldOutcome fold(
      AtomicReference<ConversationState> progress, ConversationEvent fact, List<String> drained) {
    Step step = progress.get().fold(fact);
    ConversationState folded = step.state();
    Optional<String> halt = termination.shouldHalt(folded);
    if (halt.isPresent()) {
      Step closed = folded.halted(halt.get());
      progress.set(closed.state());
      remember(closed.state().id(), step.remember());
      remember(closed.state().id(), closed.remember());
      emitter.emit(fact);
      save(progress, drained);
      return new FoldOutcome(List.of(), true);
    }
    progress.set(folded);
    remember(folded.id(), step.remember());
    emitter.emit(fact);
    save(progress, drained);
    return new FoldOutcome(step.effects(), false);
  }

  private void remember(ConversationId id, List<Message> births) {
    births.forEach(message -> memory.remember(id, message));
  }

  /** Persists {@code progress}'s current state, draining {@code drained} — exactly once. */
  private void save(AtomicReference<ConversationState> progress, List<String> drained) {
    ConversationState saved = store.save(progress.get(), List.copyOf(drained));
    drained.clear();
    progress.set(saved);
  }

  /**
   * Applies the fold-free, loop-applied {@code parked} closure transition and saves it — no message
   * is born, so unlike {@code halted} there is nothing to remember, only to persist.
   */
  private void applyParked(
      AtomicReference<ConversationState> progress,
      ToolCall call,
      ParkToken token,
      List<String> drained) {
    progress.set(progress.get().parked(call, token));
    save(progress, drained);
  }

  /**
   * Routes a resolved agenda entry to the parked executor's own {@code resume} and returns its
   * settled fact. The executor contract allows a resumed call to park again (an approved call whose
   * tool itself then parks); this generation does not support re-parking an already-parked call, so
   * that outcome fails loud rather than silently losing the call.
   */
  private ConversationEvent resumeParkedCall(
      ParkedCall parked,
      ToolResolution resolution,
      ConversationState state,
      TurnObserver observer) {
    Awaited<ConversationEvent> outcome =
        executors.toolCall().resume(parked.call(), resolution, state, observer);
    return switch (outcome) {
      case Awaited.Ready<ConversationEvent>(ConversationEvent value) -> value;
      case Awaited.Parked<ConversationEvent> _ ->
          throw new IllegalStateException(
              "resumed call "
                  + parked.call().id()
                  + " parked again; this generation does not support re-parking an already-parked"
                  + " call");
    };
  }

  private PerformOutcome perform(Effect effect, ConversationState state, TurnObserver observer) {
    return switch (effect) {
      case Effect.CallModel _ -> settled(executors.callModel().execute(state, observer));
      case Effect.ExecuteTool(var call) ->
          settledOrParked(call, executors.toolCall().execute(call, state, observer));
    };
  }

  private static PerformOutcome settled(Awaited<ConversationEvent> outcome) {
    return switch (outcome) {
      case Awaited.Ready<ConversationEvent>(ConversationEvent value) ->
          new PerformOutcome.Settled(value);
      case Awaited.Parked<ConversationEvent> _ ->
          throw new UnsupportedOperationException(
              "a model call effect parked, but model calls never park (this generation)");
    };
  }

  private static PerformOutcome settledOrParked(ToolCall call, Awaited<ConversationEvent> outcome) {
    return switch (outcome) {
      case Awaited.Ready<ConversationEvent>(ConversationEvent value) ->
          new PerformOutcome.Settled(value);
      case Awaited.Parked<ConversationEvent>(ParkToken token) ->
          new PerformOutcome.Parked(call, token);
    };
  }
}
