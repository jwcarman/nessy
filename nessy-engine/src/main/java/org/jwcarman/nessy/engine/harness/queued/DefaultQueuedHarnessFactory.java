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
package org.jwcarman.nessy.engine.harness.queued;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessConfig;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.effect.ApprovalHandler;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.engine.effect.EffectHandlers;
import org.jwcarman.nessy.engine.effect.EffectTermsSource;
import org.jwcarman.nessy.engine.effect.InferenceHandler;
import org.jwcarman.nessy.engine.effect.ToolCallHandler;
import org.jwcarman.nessy.engine.harness.InputLabels;
import org.jwcarman.nessy.engine.harness.ProviderRegistry;
import org.jwcarman.nessy.engine.history.EventStreamHistory;
import org.jwcarman.nessy.engine.history.EventStreamToolCalls;
import org.jwcarman.nessy.engine.history.Transcript;
import org.jwcarman.nessy.engine.inference.ContextAssembler;
import org.jwcarman.nessy.engine.inference.DefaultInferenceService;
import org.jwcarman.nessy.engine.inference.InferenceContextAssembler;
import org.jwcarman.nessy.engine.narration.AfterCommit;
import org.jwcarman.nessy.engine.narration.Listeners;
import org.jwcarman.nessy.engine.observability.ObservedAmbientSource;
import org.jwcarman.nessy.engine.observability.ObservedInferenceContextAssembler;
import org.jwcarman.nessy.engine.observability.ObservedMemorySource;
import org.jwcarman.nessy.engine.observability.ObservedStateSource;
import org.jwcarman.nessy.engine.observability.ObservedTurnHistories;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.engine.store.Outbox;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.tool.DefaultReplies;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.engine.trace.Traces;
import org.jwcarman.nessy.engine.work.StoredAgentWork;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes harnesses, holding the infrastructure every agent type is built from.
 *
 * <p>The split is deliberate: a caller supplies what is theirs -- the agent type's name, its input
 * type, how to render one, which model it calls, how often it polls -- and this supplies the
 * stores, the row locks, the scheduler and the codec factory. Nobody assembles a harness by hand,
 * so nobody can assemble one wrongly.
 *
 * <p><b>Shared infrastructure, not shared machinery.</b> Two harnesses use the same tables and the
 * same scheduler the way they use the same JVM. Nothing else crosses between them: each gets its
 * own codec, its own model, its own dispatcher, its own schedule, and its own rows. There is no
 * registry here holding the harnesses this made, and nothing that iterates all of them.
 */
public class DefaultQueuedHarnessFactory implements QueuedHarnessFactory, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DefaultQueuedHarnessFactory.class);

  // The engine's own decisions, made once. A tool's arguments are described by victools, a
  // message costs about its characters, and time is UTC: none of that is an application's to
  // change, so none of it is asked for. What is done to a stored byte after Jackson -- compressed,
  // encrypted -- is the backend's, chosen together with the stores it is applied to.
  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final JsonSchemaGenerator schemas = new VictoolsJsonSchemaGenerator();
  private final Clock clock = Clock.systemUTC();

  /**
   * Where agents, events, content, outstanding effects and the lock all come from -- held whole
   * rather than torn into fields of its own, because those stores are one decision chosen together,
   * and a factory holding five of its own fields would be exactly what let them drift apart.
   */
  private final QueuedBackend backend;

  private final List<NarrationListener> listeners = new CopyOnWriteArrayList<>();
  private final DefaultReplies replies;
  private final StoredAgentWork work;
  private final ThreadPoolTaskScheduler scheduler;
  private final Traces traces;
  private final ObservationRegistry observations;
  private final ProviderRegistry.Resolved providers;
  private final DefaultQueuedHarnessConfig.Defaults defaults;
  private final List<DefaultQueuedHarness<?>> harnesses = new CopyOnWriteArrayList<>();
  private final List<Listeners> tellers = new CopyOnWriteArrayList<>();
  private final List<AfterCommit> sequencers = new CopyOnWriteArrayList<>();

  /**
   * Builds an engine from what an application says it wants, which is a {@link QueuedBackend} and a
   * provider. The backend is where the stores, the transaction manager and the codec they share
   * come from, chosen together, so there is nothing for a caller to assemble and nothing for two
   * callers to assemble differently.
   *
   * <p>One factory, from every customizer that has something to say about the engine.
   *
   * <p>A list because this is what a container hands over: every {@code
   * Customizer<QueuedHarnessFactoryConfig>} bean an application declared, in order, each adding to
   * the same config before anything is built from it.
   */
  public static DefaultQueuedHarnessFactory of(
      List<Customizer<QueuedHarnessFactoryConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    QueuedHarnessFactoryConfig config = new QueuedHarnessFactoryConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return new DefaultQueuedHarnessFactory(config);
  }

  /** One customizer, for a caller that is not a container. */
  public static DefaultQueuedHarnessFactory of(Customizer<QueuedHarnessFactoryConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
  }

  /**
   * For a caller that already holds the settings, which {@link DefaultNessy} does: it takes the
   * model at its own door and hands the engine a config it has already written on.
   */
  DefaultQueuedHarnessFactory(QueuedHarnessFactoryConfig config) {
    this.backend = config.requiredBackend();
    listeners.addAll(config.listeners());
    this.replies = new DefaultReplies();
    this.work = StoredAgentWork.queued(backend, clock);
    // A timer, and only a timer: it never performs an effect (each dispatcher has its own
    // virtual-thread executor for that), it only says when to look for due work. One virtual
    // thread for the whole engine, and the engine's to stop -- see close().
    this.scheduler = new ThreadPoolTaskScheduler();
    scheduler.setVirtualThreads(true);
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("nessy-");
    scheduler.initialize();
    this.observations = config.observations();
    this.traces =
        config
            .traceCarrier()
            .map(carrier -> new Traces(observations, carrier))
            .orElseGet(() -> new Traces(observations));
    this.providers = config.providers().observed(observations);
    // What an agent type gets unless it says otherwise. Configured once, by the application,
    // where a provider and a model are an application-wide fact rather than an agent's.
    this.defaults =
        new DefaultQueuedHarnessConfig.Defaults(config.defaultProvider(), config.defaultOptions());
  }

  /** What every agent of this factory is doing, read from the stores on each call. */
  @Override
  public AgentWork work() {
    return work;
  }

  /**
   * The general form.
   *
   * <p>{@link TypeRef#parameterized} is why this works at all: a {@code TypeRef} cannot be captured
   * for a type variable, so {@code new TypeRef<AgentState<I>>() {}} would throw here. Composing one
   * from the caller's own {@code TypeRef<I>} is what lets the document's codec know a type this
   * class never sees -- and it is why nothing above ever has to name {@code AgentState}.
   */
  @Override
  public <I> QueuedHarness<I> create(
      AgentType agentType, TypeRef<I> inputType, Customizer<QueuedHarnessConfig<I>> customizer) {
    DefaultQueuedHarnessConfig<I> config =
        new DefaultQueuedHarnessConfig<>(
            agentType, inputType, defaults, mapper, schemas, observations);
    customizer.customize(config);

    Tools tools = config.tools();
    // Built here because it needs the store, which a caller has no handle on.
    DefaultQueuedHarnessConfig.Inference inference = config.inference();
    DefaultQueuedHarnessConfig.Inference.Context context = inference.context();
    ProviderId providerId = providers.choose(agentType, inference.provider());
    if (inference.modelName() == null) {
      throw new IllegalStateException(
          "agent type '" + agentType.value() + "' names no model and the factory has no default");
    }
    InferenceOptions options = inference.options();
    InferenceProvider provider = providers.resolve(agentType, providerId);
    validate(agentType, provider, options);
    context.chapters().requireTail(agentType, context.maxTail());
    if (log.isInfoEnabled()) {
      log.info(
          "NESSY INFERENCE: agent type '{}' -> {} / {}, up to {} tokens{}",
          agentType.value(),
          providerId.value(),
          options.modelName(),
          options.maxTokens(),
          propertyNames(options));
    }
    // What each kind of effect is worth, from the tools this harness bound and the harness-wide
    // defaults alone -- nothing else, so the direct door can ask the same question without
    // building a handler just to hold it.
    EffectTermsSource terms =
        new EffectTermsSource(
            tools,
            config.toolTimeout(),
            config.toolRetryPolicy(),
            config.approvalTimeout(),
            config.toolRetryPolicy(),
            inference.timeout(),
            inference.retryPolicy());
    // Observed as they are handed over, the way a tool is wrapped as it is bound: what the
    // engine is given reports its own work, and the assembler knows nothing about spans.
    Payloads payloads = backend.payloads();
    TurnHistories histories = ObservedTurnHistories.wrap(histories(), observations);
    // Everyone who hears this harness's agents: the engine's listeners, then its own, which
    // include the keeper that cuts and summarises this agent type's history when a turn ends.
    List<NarrationListener> own = new ArrayList<>(config.listeners());
    context
        .chapters()
        .keeper(
            agentType,
            backend.chapters(),
            backend.leases(),
            histories,
            provider,
            options,
            observations)
        .ifPresent(keeper -> own.add(keeper.listener()));
    Listeners telling = new Listeners(listeners, own);
    tellers.add(telling);
    // Between everything that narrates and the listeners: what a locked step narrates is held until
    // it commits, and what is narrated outside one keeps its place in the agent's order.
    AfterCommit narrator = new AfterCommit(telling);
    sequencers.add(narrator);
    InferenceContextAssembler assembler =
        ObservedInferenceContextAssembler.wrap(
            new ContextAssembler(
                histories,
                context.chapters().on() ? backend.chapters() : null,
                context.maxTail(),
                context.memory().stream()
                    .map(source -> ObservedMemorySource.wrap(source, observations))
                    .toList(),
                context.state().stream()
                    .map(source -> ObservedStateSource.wrap(source, observations))
                    .toList(),
                context.ambient().stream()
                    .map(source -> ObservedAmbientSource.wrap(source, observations))
                    .toList()),
            observations);
    // One registry, held by both halves: the store asks it what an effect is worth while
    // writing the row, the dispatcher asks it who performs one after reading it back. Two
    // lookups keyed by the same thing could disagree; one cannot.
    EffectHandlers handlers =
        new EffectHandlers(
            createInferenceHandler(
                agentType, assembler, inference, provider, config, tools, narrator, payloads,
                terms),
            createApprovalHandler(agentType, tools, narrator, terms, payloads),
            createToolCallHandler(agentType, tools, payloads, terms));
    Outbox effects = new Outbox(agentType, handlers, backend.effects());
    Backlogs<I> backlogs = backend.backlogs(config.inputType());
    DefaultQueuedHarness<I> harness =
        new DefaultQueuedHarness<>(
            agentType,
            config.policy(),
            config.renderer(),
            new InputLabels<>(agentType, config.label()),
            backend,
            backlogs::forAgent,
            effects,
            narrator,
            clock,
            config.turnPolicy(),
            traces);

    // The harness is the callback, so it has to exist before its dispatcher does -- and the
    // dispatcher must not be polling before the harness can be called back into. Three
    // statements rather than one constructor, because the cycle is real and hiding it would
    // mean leaking a half-built harness to something already able to reach it.
    // Registered before the dispatcher polls, so an answer can never arrive for an agent type
    // this process is serving but has not admitted to. The reverse -- a token for a type
    // nobody configured -- is answered as nothing awaiting, which is what it is.
    replies.register(agentType, effects, harness, backend.payloads(), tools);

    EffectDispatcher dispatcher =
        new EffectDispatcher(
            agentType,
            effects,
            handlers,
            harness,
            clock,
            scheduler,
            traces,
            config.effects().pollInterval(),
            config.effects().maxInFlight());
    // Lifecycle only: the harness holds it so that closing one closes the other. How often it
    // polls never reaches the harness.
    harness.dispatchWith(dispatcher);
    dispatcher.start();
    harnesses.add(harness);

    return harness;
  }

  private @NonNull ToolCallHandler createToolCallHandler(
      AgentType agentType, Tools tools, Payloads payloads, EffectTermsSource terms) {
    return new ToolCallHandler(
        agentType,
        tools,
        new EventStreamToolCalls(backend.events(), payloads, agentType),
        terms,
        payloads);
  }

  private @NonNull ApprovalHandler createApprovalHandler(
      AgentType agentType,
      Tools tools,
      AfterCommit narrator,
      EffectTermsSource terms,
      Payloads payloads) {
    return new ApprovalHandler(
        agentType,
        tools,
        new EventStreamToolCalls(backend.events(), payloads, agentType),
        narrator,
        terms,
        clock);
  }

  private <I> @NonNull InferenceHandler createInferenceHandler(
      AgentType agentType,
      InferenceContextAssembler assembler,
      DefaultQueuedHarnessConfig.Inference inference,
      InferenceProvider provider,
      DefaultQueuedHarnessConfig<I> config,
      Tools tools,
      AfterCommit narrator,
      Payloads payloads,
      EffectTermsSource terms) {
    return new InferenceHandler(
        agentType,
        new DefaultInferenceService(
            assembler,
            provider,
            config.requiredSystemPrompt(),
            tools.offers(),
            narrator,
            // No shape: what a queued agent answers is not constrained, because nobody is waiting
            // to read it back as a type.
            Optional.empty(),
            payloads),
        inference.options(),
        terms,
        payloads,
        narrator,
        tools);
  }

  /**
   * Somebody who hears what every agent of every harness does, attached after the fact -- for a
   * container that finds its listeners once everything else exists. Harnesses already made hear it
   * too.
   */
  public void listener(NarrationListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
  }

  /**
   * The story, for reading. An application that shows what its agents said -- a transcript page, a
   * board -- reads it through this rather than opening the tables itself, so what it reads is what
   * the engine wrote, decoded the way the engine decodes it.
   */
  public TurnHistories histories() {
    // Projected from the events rather than read from a table of its own: the story IS the events,
    // and a second shape of it would be a second thing to keep in step.
    return (type, id) ->
        new EventStreamHistory(
            backend.events(), new Transcript(backend.payloads().forAgent(id)), type, id);
  }

  /**
   * Stops every harness this factory created and the timer they shared. Inferred by Spring as the
   * bean's destroy method, and there for anyone who built an engine without Spring.
   */
  @Override
  public void close() {
    harnesses.forEach(DefaultQueuedHarness::close);
    sequencers.forEach(AfterCommit::close);
    tellers.forEach(Listeners::close);
    scheduler.shutdown();
  }

  /**
   * Where a late answer comes back in.
   *
   * <p>One for the whole factory rather than one per harness: an answer names its agent type, and
   * this routes on it.
   */
  @Override
  public Replies replies() {
    return replies;
  }

  /**
   * The adapter's say on the terms, before anything is built on them (spec §7c): a refusal fails
   * the build, named for the agent type, rather than the agent's first turn.
   */
  private static void validate(
      AgentType agentType, InferenceProvider provider, InferenceOptions options) {
    try {
      provider.validate(options);
    } catch (IllegalArgumentException refused) {
      throw new IllegalArgumentException(
          "agent type '" + agentType.value() + "': " + refused.getMessage(), refused);
    }
  }

  /** The report line's clause: names only, sorted, never a value (spec §6c). */
  static String propertyNames(InferenceOptions options) {
    return options.properties().isEmpty()
        ? ""
        : ", properties " + new TreeSet<>(options.properties().keySet());
  }
}
