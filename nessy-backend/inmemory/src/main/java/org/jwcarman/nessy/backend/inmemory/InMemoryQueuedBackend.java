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
package org.jwcarman.nessy.backend.inmemory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToIntBiFunction;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.IdentityCodec;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;

/**
 * Everything the queued door needs, with nothing behind it but this process.
 *
 * <p>The queued door is the durable one: it writes an effect down, hands the turn back, and lets a
 * dispatcher pick the work up later. All of that works here, and none of it survives a restart --
 * which makes this exactly right for a test and exactly wrong for anything that must not lose work.
 * The point is not that it is durable; it is that the door cannot tell the difference, so a test
 * that runs against this is testing the door rather than PostgreSQL.
 *
 * <p><b>Ending an agent clears what was waiting for it.</b> {@link InMemoryAgents} is handed the
 * way to do that rather than reaching for it, so the two halves stay separable; the durable pair
 * does the same thing with two statements.
 */
public final class InMemoryQueuedBackend implements QueuedBackend {

  private record Key(AgentType type, AgentId agent) {}

  private final Locks locks = new InMemoryLocks();
  private final Leases leases = new InMemoryLeases();
  private final AgentEvents events;
  private final Payloads payloads;
  private final Chapters chapters;
  private final Effects effects;
  private final InMemoryAgents agents;

  /**
   * How to empty each set of backlogs this backend has handed out.
   *
   * <p>One entry per {@link #backlogs(TypeRef)} call rather than one map of every backlog, because
   * a single map would hold several agents' input types at once and getting a typed backlog back
   * out of it would need a cast. Each call keeps its own map of its own type instead, and registers
   * the way to clear it here, so ending an agent still empties whatever was waiting for it.
   */
  private final List<ToIntBiFunction<AgentType, AgentId>> clearers = new CopyOnWriteArrayList<>();

  /**
   * For a caller that builds its own codecs: the factory is used as given for every store, payloads
   * included, so a factory that already has a storage transform is hashed after it: a payload's
   * reference then depends on what the transform writes. Use the constructor that takes the value
   * codec and the transform apart when there is a transform.
   */
  public InMemoryQueuedBackend(CodecFactory codecs) {
    this(codecs, codecs, IdentityCodec.INSTANCE);
  }

  /**
   * For a caller that has a storage transform and holds it apart from the value codec. Every store
   * is built over the two composed, as the other constructor's factory would be, except the
   * payloads, which get them apart so a payload's reference is a hash of its content before the
   * transform. With the other constructor, a factory that already includes a transform is hashed
   * after it.
   *
   * @param values the value codec, with no transform applied
   * @param transform the storage transform
   */
  public InMemoryQueuedBackend(CodecFactory values, Codec<byte[]> transform) {
    this(
        StorageCodecs.compose(
            Objects.requireNonNull(values, "values must not be null"),
            Objects.requireNonNull(transform, "transform must not be null")),
        values,
        transform);
  }

  private InMemoryQueuedBackend(CodecFactory codecs, CodecFactory values, Codec<byte[]> transform) {
    Objects.requireNonNull(codecs, "codecs must not be null");
    this.events = new InMemoryAgentEvents(codecs);
    this.payloads = new InMemoryPayloads(values, transform);
    this.chapters = new InMemoryChapters(codecs);
    this.effects = new InMemoryEffects(codecs);
    this.agents = new InMemoryAgents(this::clear);
  }

  private int clear(AgentType type, AgentId agent) {
    int abandoned = 0;
    for (ToIntBiFunction<AgentType, AgentId> clearer : clearers) {
      abandoned += clearer.applyAsInt(type, agent);
    }
    return abandoned;
  }

  @Override
  public AgentEvents events() {
    return events;
  }

  @Override
  public Payloads payloads() {
    return payloads;
  }

  @Override
  public Locks locks() {
    return locks;
  }

  @Override
  public Chapters chapters() {
    return chapters;
  }

  @Override
  public Leases leases() {
    return leases;
  }

  @Override
  public Agents agents() {
    return agents;
  }

  @Override
  public Effects effects() {
    return effects;
  }

  /**
   * The input type is taken and ignored, which is the one place this diverges visibly from the
   * durable backend and is worth saying rather than hiding. There it decides the codec that turns
   * an input into a row; here the item never leaves the process, so there is nothing to encode it
   * for. The seam still takes the type because the durable one cannot do without it, and a backend
   * interface that asked two different questions would be two interfaces.
   */
  @Override
  public <I> Backlogs<I> backlogs(TypeRef<I> inputType) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Map<Key, InMemoryBacklog<I>> mine = new ConcurrentHashMap<>();
    clearers.add(
        (type, agent) -> {
          InMemoryBacklog<I> backlog = mine.get(new Key(type, agent));
          return backlog == null ? 0 : backlog.clear();
        });
    return (type, agent) ->
        mine.computeIfAbsent(
            new Key(type, agent),
            _ -> new InMemoryBacklog<>(List.of(), () -> agents.terminated(type, agent)));
  }
}
