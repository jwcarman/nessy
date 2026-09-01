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
import javax.sql.DataSource;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.sharding.typed.ClusterShardingSettings;
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
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.spi.codec.Codecs;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

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

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(PekkoHarnessFactory.class);

  /**
   * The default memory's budget, in characters.
   *
   * <p>Arbitrary, and chosen to be SAFE rather than optimal: roughly 25k tokens, which leaves room
   * for a system prompt, tool schemas and an answer inside every current model's window. An
   * application that knows its own shape supplies its own memory.
   */
  private static final int DEFAULT_MEMORY_CHARACTERS = 100_000;

  private final ActorSystem<?> system;
  private final DataSource dataSource;
  private final ModelProvider models;
  private final int maxTokens;
  private final Set<Capability> capabilities;
  private final Executor blocking;
  private final Clock clock;
  private final ReplyTokens tokens;
  private final Traces traces;
  private final Claims claims;
  private final Replies replies;

  /**
   * Builds an engine from {@code customizer}'s settings.
   *
   * <pre>{@code
   * new PekkoHarnessFactory(engine -> engine.system(system).models(models).dataSource(ds));
   * }</pre>
   *
   * @throws IllegalStateException if a required setting was not supplied
   */
  public PekkoHarnessFactory(Consumer<EngineConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    EngineConfig config = new EngineConfig();
    customizer.accept(config);
    this.system = config.system();
    this.models = config.models();
    this.maxTokens = config.maxTokens();
    this.capabilities = config.capabilities();
    this.blocking = config.blocking();
    this.clock = config.clock();
    this.tokens = config.replyTokens();
    this.traces = config.traces();
    this.dataSource = config.dataSource().orElseGet(PekkoHarnessFactory::ownDatabase);
    this.claims = new Claims(this.dataSource);
    this.replies =
        new Replies(system, java.time.Duration.ofSeconds(10), tokens, this.traces, this.claims);
  }

  /**
   * The engine's own database, when an application supplied none.
   *
   * <p>The engine needs claims and reminders, so the engine provides them: nothing outside reads
   * either, so neither is an extension point and neither should be something an application has to
   * wire. In memory here, and initialized because it is OURS — a {@link DataSource} an application
   * supplies is never touched uninvited, which is the whole reason our DDL is named {@code
   * nessy-schema.sql} rather than {@code schema.sql}.
   */
  private static DataSource ownDatabase() {
    EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    Schemas.initialize(database);
    return database;
  }

  @Override
  public <O> Harness<O> createHarness(
      Class<O> observationType, Consumer<HarnessConfig<O>> configurer) {
    Objects.requireNonNull(observationType, "observationType must not be null");
    Objects.requireNonNull(configurer, "configurer must not be null");

    EngineHarnessConfig<O> config = new EngineHarnessConfig<>();
    configurer.accept(config);
    AgentType type = config.agentType();

    // The one moment O is statically known. It is handed to the BACKLOG STORE and goes no further:
    // the store owns the codec, the renderer and the coalescer, so nothing above it is generic.
    Codec<O> codec = Codecs.factory().create(observationType);

    Memory memory = memoryFor(config, type);
    Reminders reminders = new Reminders(dataSource);
    Model model = models.model(config.modelId());
    ToolBindings bindings = new ToolBindings(config.toolBindings(), EngineMapper.INSTANCE);

    ClusterSharding sharding = ClusterSharding.get(system);
    EntityTypeKey<NarrationActor.Command> narrationKey =
        EntityTypeKey.create(NarrationActor.Command.class, "narration-" + type.name());
    // Unloaded when nobody is LISTENING, never when nothing has happened.
    //
    // This entity's whole state is a set of live subscribers — actor refs belonging to processes
    // still listening. None of it is recoverable, so unloading it does not free state to be read
    // back later; it destroys it, and every subscriber goes deaf with no error anywhere.
    //
    // Pekko's default, "default-idle-strategy", unloads an entity after two minutes without
    // MESSAGES. Measured breaking a real session: a person read a long answer and typed a reply,
    // the turn that followed ran perfectly, finished, and published into an empty set while the
    // terminal waited out its patience. An agent is allowed to think for longer than its audience
    // takes to type.
    //
    // So the timer is off here, and NarrationActor decides for itself: when its last subscriber
    // leaves it starts a short countdown, cancelled the moment anyone subscribes again. Same
    // economy, without mistaking silence for absence.
    sharding.init(
        Entity.of(narrationKey, context -> NarrationActor.create(context.getShard()))
            .withSettings(ClusterShardingSettings.create(system).withNoPassivationStrategy()));

    BacklogStore<O> backlog =
        new BacklogStore<>(
            dataSource,
            claims,
            codec,
            JsonCodec.of(EngineMapper.INSTANCE, UserMessage.class),
            config.observationRenderer(),
            config.backlogCoalescer(),
            clock);

    Instructions instructions =
        new Instructions(
            system,
            new Instructions.Dependencies(
                type,
                memory,
                model,
                config.prompt(),
                maxTokens,
                bindings,
                capabilities,
                agentId -> narratorFor(sharding, narrationKey, agentId),
                claims,
                reminders,
                tokens,
                blocking,
                traces,
                backlog));

    AgentActor.Dependencies deps = new AgentActor.Dependencies(type, instructions, traces);

    EntityTypeKey<NessyMessage> agentKey = EntityTypeKey.create(NessyMessage.class, type.name());
    sharding.init(
        Entity.of(
                agentKey,
                context ->
                    AgentActor.create(deps, AgentId.of(context.getEntityId()), context.getShard()))
            .withStopMessage(new NessyMessage.Stop(Map.of())));

    replies.serving(type.name(), agentKey);
    return new ShardedHarness<>(type, agentKey, narrationKey, backlog, system, traces);
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

  /**
   * What the application asked for, or a default that keeps working.
   *
   * <p>The default is announced rather than assumed. It is chosen so an agent does not eventually
   * stop — not because it is a good memory — and the difference matters enough to say out loud
   * once, the same way an in-memory database does.
   */
  private <O> Memory memoryFor(EngineHarnessConfig<O> config, AgentType type) {
    Memory supplied = config.memory();
    if (supplied != null) {
      return supplied;
    }
    LOG.warn(
        "NESSY IS USING THE DEFAULT MEMORY for agent type '{}': the newest ~{} characters of"
            + " history and nothing else — no summarization, no retrieval, and the oldest turns are"
            + " simply forgotten. Supply one via HarnessConfig.memory for anything that is not a"
            + " demo.",
        type.name(),
        DEFAULT_MEMORY_CHARACTERS);
    return TranscriptMemory.recent(dataSource, type, DEFAULT_MEMORY_CHARACTERS);
  }
}
