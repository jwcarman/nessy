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
package org.jwcarman.nessy.engine;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.HarnessFactory;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * Builds harnesses on an actor system the caller already owns.
 *
 * <p>Everything shared is given here, once: the system, where state lives, how models are reached,
 * what runs blocking work. Each {@link #createHarness} call adds only what makes one kind of agent
 * different from another — which is why {@link HarnessConfig} names no infrastructure at all.
 *
 * <p><b>The system is borrowed, never owned.</b> Nothing here terminates it, and there is no
 * shutdown to call: agents are cluster entities, so they belong to the cluster rather than to
 * whoever asked for a harness.
 */
public final class PekkoHarnessFactory implements HarnessFactory {

  private final ActorSystem<?> system;
  private final Substrate substrate;
  private final ModelProvider models;
  private final int maxTokens;
  private final Set<Capability> capabilities;
  private final Executor blocking;
  private final Clock clock;
  private final ReplyTokens tokens;
  private final Traces traces;
  private final Replies replies;

  /**
   * @param maxTokens the longest answer to allow. Infrastructure rather than per-agent-kind
   *     configuration for now, because {@code HarnessConfig} has no slot for it — worth revisiting
   *     when one kind of agent needs a different ceiling from another.
   */
  public PekkoHarnessFactory(
      ActorSystem<?> system,
      Substrate substrate,
      ModelProvider models,
      int maxTokens,
      Set<Capability> capabilities,
      Executor blocking,
      Clock clock,
      ReplyTokens tokens) {
    this(
        system, substrate, models, maxTokens, capabilities, blocking, clock, tokens, Traces.noop());
  }

  /** With tracing. Everything an agent does lands under the span that caused it. */
  public PekkoHarnessFactory(
      ActorSystem<?> system,
      Substrate substrate,
      ModelProvider models,
      int maxTokens,
      Set<Capability> capabilities,
      Executor blocking,
      Clock clock,
      ReplyTokens tokens,
      Traces traces) {
    this.system = Objects.requireNonNull(system, "system must not be null");
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    this.models = Objects.requireNonNull(models, "models must not be null");
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    this.maxTokens = maxTokens;
    this.capabilities =
        Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
    this.blocking = Objects.requireNonNull(blocking, "blocking must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
    this.replies = new Replies(system, java.time.Duration.ofSeconds(10), tokens, this.traces);
  }

  @Override
  public <O> Harness<O> createHarness(
      Class<O> observationType, Consumer<HarnessConfig<O>> configurer) {
    Objects.requireNonNull(observationType, "observationType must not be null");
    Objects.requireNonNull(configurer, "configurer must not be null");

    EngineHarnessConfig<O> config = new EngineHarnessConfig<>();
    configurer.accept(config);
    AgentType type = config.agentType();

    // The one moment O is statically known. Everything erasure would otherwise cost is paid here.
    Codec<O> codec = substrate.codecs().create(observationType);
    StateTypes.of(system).register(type, observationType);

    Memory memory = new Transcripts(substrate, type);
    Claims claims = new Claims(substrate);
    Model model = models.model(config.modelId());
    ToolBindings bindings = new ToolBindings(config.toolBindings(), EngineMapper.INSTANCE);

    ClusterSharding sharding = ClusterSharding.get(system);
    EntityTypeKey<NarrationActor.Command> narrationKey =
        EntityTypeKey.create(NarrationActor.Command.class, "narration-" + type.name());
    sharding.init(Entity.of(narrationKey, context -> NarrationActor.create()));

    Turns turns =
        (agentId, turnId, input, agent, carried) ->
            TurnActor.create(
                new TurnActor.Dependencies(
                    type,
                    memory,
                    model,
                    config.prompt(),
                    maxTokens,
                    bindings,
                    capabilities,
                    narratorFor(sharding, narrationKey, agentId),
                    claims,
                    tokens,
                    blocking,
                    traces),
                agentId,
                turnId,
                input,
                agent,
                // Captured on the AGENT's thread while its receive span was open, and handed
                // through — so the turn, and everything it does, hangs off the message that asked.
                carried);

    AgentActor.Dependencies<O> deps =
        new AgentActor.Dependencies<>(
            type,
            codec,
            config.backlogCoalescer(),
            config.observationRenderer(),
            turns,
            clock,
            traces);

    EntityTypeKey<NessyMessage> agentKey = EntityTypeKey.create(NessyMessage.class, type.name());
    sharding.init(
        Entity.of(
                agentKey,
                context ->
                    AgentActor.create(deps, AgentId.of(context.getEntityId()), context.getShard()))
            .withStopMessage(new NessyMessage.Stop(Map.of())));

    replies.serving(type.name(), agentKey);
    return new ShardedHarness<>(type, agentKey, narrationKey, codec, system, traces);
  }

  /** Where the outside world answers calls parked by any agent this factory serves. */
  public Replies replies() {
    return replies;
  }

  /**
   * Narration is per agent, so the narrator is built per turn rather than closed over once. Cheap:
   * an entity ref is a routing decision, not a lookup.
   */
  private static Narrator narratorFor(
      ClusterSharding sharding, EntityTypeKey<NarrationActor.Command> key, AgentId agentId) {
    return (AgentEvent event) ->
        sharding.entityRefFor(key, agentId.value()).tell(new NarrationActor.Narrate(event));
  }
}
