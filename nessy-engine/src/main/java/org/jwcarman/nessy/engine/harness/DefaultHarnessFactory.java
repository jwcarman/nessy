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
package org.jwcarman.nessy.engine.harness;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.HarnessFactory;
import org.jwcarman.nessy.api.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.effect.ApprovalHandler;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.engine.effect.EffectHandlers;
import org.jwcarman.nessy.engine.effect.InferenceHandler;
import org.jwcarman.nessy.engine.effect.ToolCallHandler;
import org.jwcarman.nessy.engine.inference.ContextAssembler;
import org.jwcarman.nessy.engine.inference.DefaultInferenceService;
import org.jwcarman.nessy.engine.inference.InferenceContextAssembler;
import org.jwcarman.nessy.engine.inference.InferenceRecorder;
import org.jwcarman.nessy.engine.observability.ObservedAmbientSource;
import org.jwcarman.nessy.engine.observability.ObservedInferenceContextAssembler;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.engine.observability.ObservedInferenceRecorder;
import org.jwcarman.nessy.engine.observability.ObservedSummarizer;
import org.jwcarman.nessy.engine.observability.ObservedTurnHistories;
import org.jwcarman.nessy.engine.store.AgentHistoryStore;
import org.jwcarman.nessy.engine.store.AgentStateRepository;
import org.jwcarman.nessy.engine.store.AgentStateStore;
import org.jwcarman.nessy.engine.store.EffectStore;
import org.jwcarman.nessy.engine.store.InferenceContexts;
import org.jwcarman.nessy.engine.store.JdbcEffectStore;
import org.jwcarman.nessy.engine.store.JdbcHistoryStore;
import org.jwcarman.nessy.engine.store.JdbcInferenceContexts;
import org.jwcarman.nessy.engine.store.StorageCodec;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.token.CharacterCountEstimator;
import org.jwcarman.nessy.engine.tool.DefaultReplies;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.engine.trace.Traces;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes harnesses, holding the infrastructure every agent type is built from.
 *
 * <p>The split is deliberate: a caller supplies what is theirs -- the agent type's name, its
 * observation type, how to render one, which model it calls, how often it polls -- and this
 * supplies the stores, the transaction template, the scheduler and the codec factory. Nobody
 * assembles a harness by hand, so nobody can assemble one wrongly.
 *
 * <p><b>Shared infrastructure, not shared machinery.</b> Two harnesses use the same tables and the
 * same scheduler the way they use the same JVM. Nothing else crosses between them: each gets its
 * own codec, its own model, its own dispatcher, its own schedule, and its own rows. There is no
 * registry here holding the harnesses this made, and nothing that iterates all of them.
 */
public class DefaultHarnessFactory implements HarnessFactory, AutoCloseable {

  // The engine's own decisions, made once. A row is encoded by Jackson, a tool's arguments are
  // described by victools, a message costs about its characters, and time is UTC: none of that
  // is an application's to change, so none of it is asked for. What is done to the bytes AFTER
  // Jackson -- compressed, encrypted -- is the application's, and comes from the config.
  private final CodecFactory codecs;
  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final InputSchemaGenerator schemas = new VictoolsInputSchemaGenerator();
  private final Clock clock = Clock.systemUTC();

  private final AgentStateRepository states;
  private final JdbcHistoryStore history;
  private final JdbcEffectStore effectRows;
  private final JdbcInferenceContexts contexts;
  private final InferenceRecorder recorder;
  private final TransactionTemplate transactions;
  private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
  private final ReplyTokens replyTokens;
  private final DefaultReplies replies;
  private final ThreadPoolTaskScheduler scheduler;
  private final Traces traces;
  private final ObservationRegistry observations;

  private final DefaultHarnessConfig.Defaults defaults;
  private final List<DefaultHarness<?>> harnesses = new CopyOnWriteArrayList<>();
  private final List<Listeners> tellers = new CopyOnWriteArrayList<>();

  /**
   * Builds an engine from what an application says it wants, which is a {@code DataSource} and a
   * provider. Everything that touches the database is built here from that one {@code DataSource}
   * -- the stores, the transaction manager, the one client they share -- so there is nothing for a
   * caller to assemble and nothing for two callers to assemble differently.
   */
  public DefaultHarnessFactory(Consumer<EngineConfig> customizer) {
    this(configured(customizer));
  }

  private static EngineConfig configured(Consumer<EngineConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    EngineConfig config = new EngineConfig();
    customizer.accept(config);
    return config;
  }

  /**
   * For a caller that already holds the settings, which {@link DefaultNessy} does: it takes the
   * model at its own door and hands the engine a config it has already written on.
   */
  DefaultHarnessFactory(EngineConfig config) {
    DataSource dataSource = config.requiredDataSource();
    CodecFactory jackson = new JacksonCodecFactory(JsonMapper.builder().build());
    this.codecs = config.storage().map(t -> StorageCodec.of(t).after(jackson)).orElse(jackson);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    this.states = new AgentStateRepository(jdbc);
    this.history = new JdbcHistoryStore(jdbc, codecs, new CharacterCountEstimator());
    this.effectRows = new JdbcEffectStore(jdbc, codecs);
    this.contexts = new JdbcInferenceContexts(jdbc, codecs, clock);
    this.recorder = config.recordInferenceContexts() ? contexts : InferenceRecorder.NONE;
    this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    listeners.addAll(config.listeners());
    this.replyTokens = config.replyTokens();
    this.replies = new DefaultReplies(replyTokens);
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
    // What an agent type gets unless it says otherwise. Configured once, by the application,
    // where a provider and a model are an application-wide fact rather than an agent's.
    this.defaults =
        new DefaultHarnessConfig.Defaults(config.requiredProvider(), config.requiredOptions());
  }

  /**
   * The general form.
   *
   * <p>{@link TypeRef#parameterized} is why this works at all: a {@code TypeRef} cannot be captured
   * for a type variable, so {@code new TypeRef<AgentState<O>>() {}} would throw here. Composing one
   * from the caller's own {@code TypeRef<O>} is what lets the document's codec know a type this
   * class never sees -- and it is why nothing above ever has to name {@code AgentState}.
   */
  @Override
  public <O> Harness<O> create(TypeRef<O> observationType, Consumer<HarnessConfig<O>> customizer) {
    DefaultHarnessConfig<O> config =
        new DefaultHarnessConfig<>(observationType, defaults, mapper, schemas, observations);
    customizer.accept(config);

    TypeRef<AgentState<O>> stateType =
        TypeRef.parameterized(AgentState.class, config.observationType());

    AgentType agentType = config.requiredAgentType();
    Tools tools = config.tools();
    // Everyone who hears this harness's agents: the engine's listeners, then its own.
    Listeners narrator = new Listeners(listeners, config.listeners());
    tellers.add(narrator);
    // Built here because it needs the store, which a caller has no handle on.
    DefaultHarnessConfig.Inference inference = config.inference();
    DefaultHarnessConfig.Inference.Context context = inference.context();
    // Observed as they are handed over, the way a tool is wrapped as it is bound: what the
    // engine is given reports its own work, and the assembler knows nothing about spans.
    InferenceContextAssembler assembler =
        ObservedInferenceContextAssembler.wrap(
            new ContextAssembler(
                ObservedTurnHistories.wrap(history, observations),
                context.summaries().stream()
                    .map(source -> ObservedSummarizer.wrap(source, observations))
                    .toList(),
                context.maxTail(),
                context.ambient().stream()
                    .map(source -> ObservedAmbientSource.wrap(source, observations))
                    .toList()),
            observations);
    // One registry, held by both halves: the store asks it what an effect is worth while
    // writing the row, the dispatcher asks it who performs one after reading it back. Two
    // lookups keyed by the same thing could disagree; one cannot.
    EffectHandlers handlers =
        new EffectHandlers(
            createInferenceHandler(agentType, assembler, inference, config, tools, narrator),
            createApprovalHandler(agentType, tools, narrator, config),
            createToolCallHandler(agentType, tools, narrator, config));
    EffectStore effects = new EffectStore(agentType, handlers, effectRows);
    DefaultHarness<O> harness =
        new DefaultHarness<>(
            agentType,
            config.coalescer(),
            new AgentStateStore<>(agentType, codecs.create(stateType), states),
            new AgentHistoryStore<>(agentType, config.renderer(), history),
            effects,
            transactions,
            narrator,
            clock,
            traces);

    // The harness is the callback, so it has to exist before its dispatcher does -- and the
    // dispatcher must not be polling before the harness can be called back into. Three
    // statements rather than one constructor, because the cycle is real and hiding it would
    // mean leaking a half-built harness to something already able to reach it.
    // Registered before the dispatcher polls, so an answer can never arrive for an agent type
    // this process is serving but has not admitted to. The reverse -- a token for a type
    // nobody configured -- is answered as nothing awaiting, which is what it is.
    replies.register(agentType, effects, harness);

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

  private <O> @NonNull ToolCallHandler createToolCallHandler(
      AgentType agentType, Tools tools, Listeners narrator, DefaultHarnessConfig<O> config) {
    return new ToolCallHandler(
        agentType,
        tools,
        history.forAgentType(agentType),
        replyTokens,
        narrator,
        config.toolTimeout(),
        config.toolRetryPolicy(),
        clock);
  }

  private <O> @NonNull ApprovalHandler createApprovalHandler(
      AgentType agentType, Tools tools, Listeners narrator, DefaultHarnessConfig<O> config) {
    return new ApprovalHandler(
        agentType,
        tools,
        history.forAgentType(agentType),
        replyTokens,
        narrator,
        config.approvalTimeout(),
        config.toolRetryPolicy(),
        clock);
  }

  private <O> @NonNull InferenceHandler createInferenceHandler(
      AgentType agentType,
      InferenceContextAssembler assembler,
      DefaultHarnessConfig.Inference inference,
      DefaultHarnessConfig<O> config,
      Tools tools,
      Listeners narrator) {
    return new InferenceHandler(
        agentType,
        new DefaultInferenceService(
            assembler,
            ObservedInferenceProvider.wrap(inference.provider(), observations),
            config.requiredSystemPrompt(),
            tools.offers(),
            narrator,
            ObservedInferenceRecorder.wrap(recorder, observations)),
        inference.options(),
        inference.timeout(),
        inference.retryPolicy());
  }

  /**
   * Somebody who hears what every agent of every harness does, attached after the fact -- for a
   * container that finds its listeners once everything else exists. Harnesses already made hear it
   * too.
   */
  public void listener(AgentEventListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
  }

  /** What each model call was shown, read-only. */
  public InferenceContexts inferenceContexts() {
    return contexts;
  }

  /**
   * The story, for reading. An application that shows what its agents said -- a transcript page, a
   * board -- reads it through this rather than opening the tables itself, so what it reads is what
   * the engine wrote, decoded the way the engine decodes it.
   */
  public TurnHistories histories() {
    return history;
  }

  /**
   * Stops every harness this factory created and the timer they shared. Inferred by Spring as the
   * bean's destroy method, and there for anyone who built an engine without Spring.
   */
  @Override
  public void close() {
    harnesses.forEach(DefaultHarness::close);
    tellers.forEach(Listeners::close);
    scheduler.shutdown();
  }

  /**
   * Where a late answer comes back in.
   *
   * <p>One for the whole factory rather than one per harness: a reply token is opaque, so whoever
   * holds one cannot say which kind of agent it belongs to and could never pick a harness. This
   * reads the agent type out of the token and routes on it.
   */
  @Override
  public Replies replies() {
    return replies;
  }
}
