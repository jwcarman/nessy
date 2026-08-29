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

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.codec.CodecPipeline;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelDescription;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * Builds harnesses on an {@code ActorSystem} the caller already owns.
 *
 * <p>Everything shared is given here, once: the system, the store, the model provider, the tools.
 * Each {@link #create} call adds only what makes one kind of agent different from another
 * (engine-extraction spec §2.1).
 *
 * <p><b>The system is borrowed, never owned.</b> It must be built around {@link SpawnProtocol},
 * because Pekko refuses to create a top-level actor from outside a system that has a custom user
 * guardian — which is exactly why a harness handed an existing system can never BE that guardian
 * (spec §3.1). {@link Harness#shutdown()} stops the subtree this spawned and nothing else;
 * terminating the caller's system is not this class's business.
 */
public final class PekkoHarnessFactory implements HarnessFactory {

  private static final Duration SPAWN_PATIENCE = Duration.ofSeconds(10);
  private static final int MODEL_WORKERS = 4;
  private static final int TOOL_WORKERS = 8;

  private final ActorSystem<SpawnProtocol.Command> system;
  private final Substrate substrate;
  private final ModelProvider models;
  private final String defaultModelId;
  private final AgentModel prebuilt;
  private final long prebuiltBudget;
  private final AgentTools tools;
  private final MeterRegistry meters;
  private final Traces traces;
  private final java.util.concurrent.atomic.AtomicInteger roots =
      new java.util.concurrent.atomic.AtomicInteger();
  private final Clock clock;
  private final Executor blocking;

  /**
   * @param system the caller's actor system — borrowed, never terminated
   * @param substrate where documents and journals live
   * @param models resolves a model by name
   * @param defaultModelId the model an agent gets when its config names none
   * @param tools what agents may call
   * @param pipeline the transforms every stored payload passes through, installed on {@code system}
   *     so the actor serializer applies exactly what {@code Substrate} does
   */
  public PekkoHarnessFactory(
      ActorSystem<SpawnProtocol.Command> system,
      Substrate substrate,
      ModelProvider models,
      String defaultModelId,
      AgentTools tools,
      MeterRegistry meters,
      Traces traces,
      Clock clock,
      Executor blocking,
      CodecPipeline pipeline) {
    this.system = Objects.requireNonNull(system, "system must not be null");
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    this.models = Objects.requireNonNull(models, "models must not be null");
    this.defaultModelId = Objects.requireNonNull(defaultModelId, "defaultModelId must not be null");
    this.prebuilt = null;
    this.prebuiltBudget = 0;
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.blocking = Objects.requireNonNull(blocking, "blocking must not be null");
    EngineCodecs.of(system).use(Objects.requireNonNull(pipeline, "pipeline must not be null"));
  }

  /**
   * For a host that already has its model — a scripted one in a test, or anything that is not a
   * {@code Model} behind a {@code ModelProvider}.
   *
   * <p>The budget must be given, because it is normally DERIVED from the model's own context window
   * and a pre-built {@link AgentModel} cannot be asked. That is the trade: this door skips the
   * capability and window checks the provider door performs, so production should not use it.
   *
   * @param budgetTokens how much context a turn may read
   */
  public PekkoHarnessFactory(
      ActorSystem<SpawnProtocol.Command> system,
      Substrate substrate,
      AgentModel model,
      long budgetTokens,
      AgentTools tools,
      MeterRegistry meters,
      Traces traces,
      Clock clock,
      Executor blocking,
      CodecPipeline pipeline) {
    this.system = Objects.requireNonNull(system, "system must not be null");
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    this.models = null;
    this.defaultModelId = null;
    this.prebuilt = Objects.requireNonNull(model, "model must not be null");
    if (budgetTokens < 1) {
      throw new IllegalArgumentException("budgetTokens must be at least 1");
    }
    this.prebuiltBudget = budgetTokens;
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.blocking = Objects.requireNonNull(blocking, "blocking must not be null");
    EngineCodecs.of(system).use(Objects.requireNonNull(pipeline, "pipeline must not be null"));
  }

  /**
   * The String door, and the real implementation.
   *
   * <p>Overridden rather than inherited because the engine is String-observation only for now —
   * {@code AgentActor}, {@code Backlogs} and the renderer are all bound to {@code String} beneath
   * the harness. Doing the work here keeps the types honest: nothing has to cast a {@code
   * HarnessConfig<O>} it privately knows is a {@code HarnessConfig<String>}.
   */
  @Override
  public PekkoHarness create(HarnessCustomizer<String> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    HarnessConfig<String> config = new HarnessConfig<>();
    config.renderer(text -> List.of(new TextBlock(text)));
    customizer.customize(config);

    AgentModel agentModel;
    long budget;
    if (prebuilt != null) {
      agentModel = prebuilt;
      budget = prebuiltBudget;
    } else {
      Model model = resolve(config);
      ModelDescription description = model.describe();
      ModelSettings settings = new ModelSettings(config.maxTokens(), Set.of());
      settings.requireSatisfiedBy(description);
      budget = budgetFor(description, settings);
      agentModel =
          new ProviderAgentModel(model, meters, config.maxTokens(), config.systemPrompt(), tools);
    }

    Memories memories = new Memories(substrate, budget);
    Claims claims = new Claims(substrate);

    ActorRef<HarnessActor.Command> root =
        spawnRoot(
            config.type(),
            HarnessActor.create(
                new HarnessActor.Wiring(
                    config.type(),
                    agentModel,
                    tools,
                    memories,
                    config.coalescer(),
                    config.renderer(),
                    traces,
                    clock,
                    blocking,
                    MODEL_WORKERS,
                    TOOL_WORKERS,
                    config.approvalTerm(),
                    claims)));

    return new PekkoHarness(config.type(), root, system, traces);
  }

  /**
   * Not yet supported: the engine below this door speaks {@code String}.
   *
   * <p>Generalising {@code AgentActor}, {@code Backlogs} and the renderer to {@code O} is its own
   * change. Until then a typed observation is the caller's to render.
   */
  @Override
  public <O> Harness<O> create(Class<O> observationType, HarnessCustomizer<O> customizer) {
    Objects.requireNonNull(observationType, "observationType must not be null");
    throw new UnsupportedOperationException(
        "this engine takes String observations only; AgentActor, Backlogs and the renderer are"
            + " bound to String beneath the harness. Use create(customizer). Asked for: "
            + observationType.getName());
  }

  /**
   * Spawns the engine's subtree top-level, through the caller's {@link SpawnProtocol}.
   *
   * <p>Blocking here is deliberate and bounded: a harness that returned before its tree existed
   * would hand back something whose first {@code observe} raced the wiring.
   */
  private ActorRef<HarnessActor.Command> spawnRoot(
      AgentType agentType, Behavior<HarnessActor.Command> behavior) {
    // Local routing parents agents under the harness, so it needs one harness per type; sharding
    // routes every harness to the same entity and needs no such rule.
    if (!RoutingStrategy.isClustered(system)) {
      LocalAgentTypes.of(system).reserve(agentType);
    }
    String name = "nessy-harness-" + agentType.name() + "-" + roots.incrementAndGet();
    return AskPattern.<SpawnProtocol.Command, ActorRef<HarnessActor.Command>>ask(
            system,
            replyTo -> new SpawnProtocol.Spawn<>(behavior, name, Props.empty(), replyTo),
            SPAWN_PATIENCE,
            system.scheduler())
        .toCompletableFuture()
        .join();
  }

  private Model resolve(HarnessConfig<String> config) {
    String id = config.modelName().orElse(defaultModelId);
    Model model = models.model(id);
    if (model == null) {
      throw new IllegalArgumentException(
          "no model named '%s' from provider %s".formatted(id, models.name()));
    }
    return model;
  }

  /**
   * What a turn may read: the model's real window, less the answer it is allowed to write.
   *
   * <p>This is the number that used to be typed into a config file. Nobody could get it right,
   * because only the model knows the window — which is why the budget was a promise nothing kept
   * until {@code ModelDescription} existed.
   */
  private static long budgetFor(ModelDescription description, ModelSettings settings) {
    return description.contextWindow() - settings.maxTokens();
  }
}
