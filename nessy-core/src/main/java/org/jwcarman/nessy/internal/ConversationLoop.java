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
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Effect;
import org.jwcarman.nessy.api.conversation.Step;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.message.Message;
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
 * <p>The cycle, per fact: ask the state to fold it; consult the termination policy (after every
 * fold — a law, not a list of check sites); tell Memory the fold's message births; emit the fact on
 * the system channel; persist; then perform the emitted effects, each yielding the next fact. A
 * halt discards unperformed effects — intents, not obligations — and applies the closure transition
 * {@code halted(reason)}.
 *
 * <p>Durability: the most recent state is saved on every exit path, including exceptions — the
 * progress-holder contract. Parks: this generation refuses them loudly (there is nowhere to park
 * to); {@link #resume} is the seam where the durable generation lands.
 */
public final class ConversationLoop {

  private static final Set<ConversationStatus> RESUMABLE =
      Set.of(ConversationStatus.IDLE, ConversationStatus.COMPLETE, ConversationStatus.FAILED);

  private final EffectExecutors executors;
  private final Memory memory;
  private final TerminationPolicy termination;
  private final ConversationStore store;
  private final EventEmitter emitter;
  private final ObservationRegistry observations;

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

  /** Runs one segment to completion. See the §6 resume-refusal contract for the status guard. */
  public RunOutcome run(
      ConversationId id, ConversationEvent.AgentTold input, TurnObserver observer) {
    Objects.requireNonNull(observer, "observer must not be null");
    Observation observation = EngineObservations.run(observations, id);
    try (var _ = observation.openScope()) {
      ConversationState loaded =
          store
              .load(id)
              .map(ConversationStore.Loaded::state)
              .orElseGet(() -> ConversationState.newConversation(id));
      if (!RESUMABLE.contains(loaded.status())) {
        throw new IllegalStateException(
            "conversation " + id + " is in flight (" + loaded.status() + "); refusing to run");
      }
      AtomicReference<ConversationState> progress = new AtomicReference<>(loaded);
      try {
        return new RunOutcome.Completed(drive(progress, input, observer));
      } finally {
        try {
          progress.set(store.save(progress.get(), List.of()));
        } catch (StaleStateException e) {
          // This generation is still single-driver: nothing else can move the stored version
          // out from under us, so this can never actually fire here. It is caught anyway so a
          // future concurrent driver (Task 4: the winning driver owns the base) can never let a
          // finally-holder save mask the primary exception this block exists to let propagate.
        }
      }
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }

  public RunOutcome resume(
      ConversationId id, ParkToken token, ToolResolution resolution, TurnObserver observer) {
    Objects.requireNonNull(observer, "observer must not be null");
    throw new UnsupportedOperationException(
        "this assembly never parks, so there is nothing to resume for "
            + id
            + " (token "
            + token
            + ", resolution "
            + resolution
            + ")");
  }

  private ConversationState drive(
      AtomicReference<ConversationState> progress, ConversationEvent first, TurnObserver observer) {
    ConversationState state = progress.get();
    Deque<Effect> queue = new ArrayDeque<>();
    ConversationEvent fact = first;
    while (true) {
      Step step = state.fold(fact);
      state = step.state();
      Optional<String> halt = termination.shouldHalt(state);
      if (halt.isPresent()) {
        Step closed = state.halted(halt.get());
        state = closed.state();
        progress.set(state);
        remember(state.id(), step.remember());
        remember(state.id(), closed.remember());
        emitter.emit(fact);
        state = store.save(state, List.of());
        progress.set(state);
        return state;
      }
      progress.set(state);
      remember(state.id(), step.remember());
      emitter.emit(fact);
      state = store.save(state, List.of());
      progress.set(state);
      step.effects().forEach(queue::addLast);
      if (queue.isEmpty()) {
        return state;
      }
      fact = perform(queue.pollFirst(), state, observer);
    }
  }

  private void remember(ConversationId id, List<Message> births) {
    births.forEach(message -> memory.remember(id, message));
  }

  private ConversationEvent perform(Effect effect, ConversationState state, TurnObserver observer) {
    Awaited<ConversationEvent> outcome =
        switch (effect) {
          case Effect.CallModel _ -> executors.callModel().execute(state, observer);
          case Effect.ExecuteTool(var call) -> executors.toolCall().execute(call, state, observer);
        };
    return switch (outcome) {
      case Awaited.Ready<ConversationEvent>(ConversationEvent value) -> value;
      case Awaited.Parked<ConversationEvent> _ ->
          throw new UnsupportedOperationException(
              "this assembly cannot park, but performing " + effect + " asked to");
    };
  }
}
