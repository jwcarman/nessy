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
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.LaneEntry;
import org.jwcarman.nessy.api.event.ListenerRegistry;
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
   * drive with — {@link #resume} has nothing to drive before the first agent is built. Every agent
   * built from this harness re-wires it, last build wins; a harness meant to field {@link #resume}
   * calls is a one-agent harness in practice, the common case this seam serves.
   */
  private ConversationLoop loop;

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

  /** {@link AgentBuilder#build()}'s wire-through: the loop {@link #resume} will drive with. */
  void loop(ConversationLoop loop) {
    this.loop = Objects.requireNonNull(loop, "loop must not be null");
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
   * {@link #resume} shares with {@code tell}: the lane absorbs the answer, the status pointer says
   * what happens next.
   *
   * @throws IllegalArgumentException if {@code token} names no conversation this store still parks
   */
  public RunOutcome resume(ParkToken token, ToolResolution resolution, TurnObserver observer) {
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(resolution, "resolution must not be null");
    Objects.requireNonNull(observer, "observer must not be null");
    ConversationId id =
        store
            .findParkConversation(token)
            .orElseThrow(
                () -> new IllegalArgumentException("unknown or settled park token: " + token));
    if (!store.consumeToken(token)) {
      return loop.drive(id, observer); // idempotent re-delivery: read current truth, do not replay
    }
    store.appendLane(id, LaneEntry.resolved(token, resolution));
    return loop.drive(id, observer);
  }
}
