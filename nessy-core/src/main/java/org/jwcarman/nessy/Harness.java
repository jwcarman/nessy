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
package org.jwcarman.nessy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.AgendaItem;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.internal.ConversationLoop;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * The application's infrastructure, assembled once and shared by every agent it builds.
 *
 * <p>Nessy's front door is a two-builder story, disjoint by design (design §17's razor). A {@code
 * Harness} owns the substrate — the model provider, session store, observation registry, and object
 * mapper — that make sense once per application, not once per agent; none of it is overridable from
 * {@link AgentBuilder}, which owns identity instead. {@link #defaultModel()} and this harness's
 * {@link org.jwcarman.nessy.api.event.ListenerRegistry} are <em>seeded</em> rather than owned
 * outright: an agent may supply its own model, and always gets its own registrations appended after
 * the harness's, via {@link org.jwcarman.nessy.api.event.ListenerRegistry#extendedWith}.
 *
 * <p>{@link #agent()} returns an {@link AgentBuilder} pre-wired with this harness's shared pieces,
 * ready to be given the identity — model, system prompt, tools, policies — that makes it a
 * particular agent. The odd-one-out agent (a different provider, a different store) is a second
 * harness, never an override on this one.
 */
public final class Harness {

  private final ModelProvider provider;
  private final ConversationStore store;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;
  private final String defaultModel;
  private final ListenerRegistry registry;

  /**
   * Wired by {@link AgentBuilder#build()}, once an agent's own {@link ConversationLoop} exists to
   * drive with — {@link #resume} has nothing to drive before the first agent is built. An {@code
   * AtomicReference} because {@link #loop(ConversationLoop, ListenerRegistry)} and {@link #resume}
   * may run on different threads (a build on one, a webhook callback on another) with no other
   * synchronization between them — unsafe publication of a plainly-written reference is a real
   * hazard here, not a theoretical one.
   */
  private final AtomicReference<ConversationLoop> loop = new AtomicReference<>();

  /**
   * The last-built agent's own {@link ListenerRegistry} — this harness's {@link #registry} extended
   * with that agent's own declared registrations (design §17's seeding), the exact instance {@link
   * AgentBuilder#build()} hands to its {@code GatedToolCallExecutor} as the emitter the in-process
   * tee narrates on. {@link #progress} emits here rather than on {@link #registry} so the two
   * progress lanes — the tee up close, the token from afar — reach the same audience: a harness- or
   * agent-declared {@code ToolProgress} listener hears both, never just one. An {@code
   * AtomicReference} for the same cross-thread-publication reason as {@link #loop}.
   */
  private final AtomicReference<ListenerRegistry> agentRegistry = new AtomicReference<>();

  /**
   * How many agents this harness has built. A second (or later) registration means {@link #resume}
   * and {@link #progress} can no longer know which agent's loop or registry — which tools, which
   * grants, which policy, which listeners — a given park token belongs to: parks do not yet carry
   * agent identity (a design escalation, out of this generation's scope), so both refuse outright
   * rather than silently routing through the wrong agent.
   */
  private final AtomicInteger loopRegistrations = new AtomicInteger();

  Harness(
      ModelProvider provider,
      ConversationStore store,
      ObservationRegistry observations,
      ObjectMapper mapper,
      String defaultModel,
      ListenerRegistry registry) {
    this.provider = provider;
    this.store = store;
    this.observations = observations;
    this.mapper = mapper;
    this.defaultModel = defaultModel;
    this.registry = registry;
  }

  /**
   * A fresh {@link AgentBuilder}, pre-wired with this harness's infrastructure, over the {@code
   * String} vocabulary — the degenerate, single-text-block case. Defaults to {@link
   * InputRenderer#text()}.
   */
  public AgentBuilder<String> agent() {
    return new AgentBuilder<>(this, String.class, InputRenderer.text());
  }

  /**
   * A fresh {@link AgentBuilder} over an application-owned input vocabulary {@code I} — typically a
   * sealed interface of records. Defaults to {@link InputRenderer#json(ObjectMapper)} over this
   * harness's own mapper; override with {@link AgentBuilder#renderer(InputRenderer)}.
   */
  public <I> AgentBuilder<I> agent(Class<I> vocabulary) {
    Objects.requireNonNull(vocabulary, "vocabulary must not be null");
    return new AgentBuilder<>(this, vocabulary, InputRenderer.json(mapper));
  }

  ModelProvider provider() {
    return provider;
  }

  ConversationStore store() {
    return store;
  }

  ObservationRegistry observations() {
    return observations;
  }

  ObjectMapper mapper() {
    return mapper;
  }

  String defaultModel() {
    return defaultModel;
  }

  ListenerRegistry registry() {
    return registry;
  }

  /**
   * {@link AgentBuilder#build()}'s wire-through: the loop {@link #resume} will drive with, and the
   * agent-extended registry {@link #progress} will emit on — the same registry instance the built
   * agent's own tee narrates {@link ToolProgress} onto.
   */
  void loop(ConversationLoop loop, ListenerRegistry agentRegistry) {
    this.loop.set(Objects.requireNonNull(loop, "loop must not be null"));
    this.agentRegistry.set(Objects.requireNonNull(agentRegistry, "agentRegistry must not be null"));
    loopRegistrations.incrementAndGet();
  }

  /**
   * Answers a parked call, watched by no one ({@link TurnObserver#noop()}).
   *
   * @see #resume(ParkToken, ToolResolution, TurnObserver)
   */
  public RunOutcome resume(ParkToken token, ToolResolution resolution) {
    return resume(token, resolution, TurnObserver.noop());
  }

  /**
   * Answers a parked call: {@code token} names a wait some prior turn is durably patient for.
   * Unknown or already-settled tokens are rejected loud rather than silently dropped; a token this
   * store still recognizes but has already consumed is redelivery (every real transport is
   * at-least-once) — the call is not replayed, the drive simply reads whatever the first delivery
   * already produced. Either way, appending always succeeds and driving is the same re-entrant act
   * {@link #resume} shares with {@code tell}: the agenda absorbs the answer, the status pointer
   * says what happens next.
   *
   * @throws IllegalArgumentException if {@code token} names no conversation this store still parks
   * @throws IllegalStateException if more than one agent has been built from this harness — {@code
   *     resume} cannot yet tell which agent's loop a token belongs to (see {@link
   *     #loopRegistrations}) — or if no agent has been built at all, reachable when a durable store
   *     carries parks left behind by a prior process and this one never called {@link #agent()}
   */
  public RunOutcome resume(ParkToken token, ToolResolution resolution, TurnObserver observer) {
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(resolution, "resolution must not be null");
    Objects.requireNonNull(observer, "observer must not be null");
    int agents = loopRegistrations.get();
    if (agents > 1) {
      throw new IllegalStateException(
          "resume is single-agent this generation: " + agents + " agents built");
    }
    ConversationId id =
        store
            .findParkConversation(token)
            .orElseThrow(
                () -> new IllegalArgumentException("unknown or settled park token: " + token));
    if (agents == 0) {
      throw new IllegalStateException(
          "no agent built on this harness — resume has no loop to drive with");
    }
    if (!store.consumeToken(token)) {
      // idempotent re-delivery: read current truth, do not replay
      return loop.get().drive(id, observer);
    }
    store.appendAgenda(id, AgendaItem.resolved(token, resolution));
    return loop.get().drive(id, observer);
  }

  /**
   * The remote signal channel: a tool still running out in the world reports {@code message}
   * against the wait it parked under. {@code token} is only ever peeked, via {@link
   * ConversationStore#findPark}, never consumed — this is narration, not a resolution, and the wait
   * itself remains exactly as resumable afterward as it was before. An unknown or already-settled
   * token is not an error — nor is a token that settles between the peek and the conversation-id
   * lookup below, a race against a concurrent {@link #resume}; either way the signal simply has
   * nowhere left to land, so it is dropped and {@code false} says so. A live token emits {@link
   * ToolProgress} on the (single) built agent's own system channel — the same {@link
   * ListenerRegistry} the in-process tee narrates on — reaching harness-seeded and agent-declared
   * listeners alike, the identical audience the tee reaches, carrying the park's own conversation
   * and call id, and returns {@code true}.
   *
   * @throws IllegalStateException if more than one agent has been built from this harness — {@code
   *     progress} cannot yet tell which agent's registry a token belongs to (see {@link
   *     #loopRegistrations}) — or if no agent has been built at all, reachable when a durable store
   *     carries parks left behind by a prior process and this one never called {@link #agent()}
   */
  public boolean progress(ParkToken token, String message) {
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(message, "message must not be null");
    int agents = loopRegistrations.get();
    if (agents > 1) {
      throw new IllegalStateException(
          "progress is single-agent this generation: " + agents + " agents built");
    }
    Optional<ParkedCall> park = store.findPark(token);
    if (park.isEmpty()) {
      return false;
    }
    Optional<ConversationId> conversationId = store.findParkConversation(token);
    if (conversationId.isEmpty()) {
      return false; // settled concurrently between the two reads — dropped, not an error
    }
    if (agents == 0) {
      throw new IllegalStateException(
          "no agent built on this harness — progress has no registry to emit on");
    }
    agentRegistry
        .get()
        .emit(new ToolProgress(conversationId.get(), park.get().call().id(), message));
    return true;
  }
}
