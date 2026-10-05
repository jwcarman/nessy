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

package org.jwcarman.nessy.engine.harness.direct;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.OutputReader;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.effect.ApprovalHandler;
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
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes direct harnesses that share what should be shared.
 *
 * <p>What sits here rather than on a harness is what an application owns once: where events are
 * written, where payloads are kept, who provides inference, and what keeps two callers off one
 * agent. A harness adds what is its own -- a type, a prompt, its tools, what it is shown.
 *
 * <p><b>Closeable, which the door's interface used to say nothing here was.</b> Enforcing the three
 * deadlines this door now enforces (design record {@code 2026-09-25-locks-as-plumbing}, {@code 4c})
 * means every effect runs on a virtual thread rather than the caller's, so there is one executor --
 * this one, shared by every harness this factory makes. A turn still ends when {@code ask} returns;
 * what outlives the call is an abandoned effect whose caller has already been told it failed.
 *
 * <p>One executor here rather than one per harness, because a thread-per-task executor over virtual
 * threads holds nothing while idle: a harness has nothing to own and nothing to release. The
 * alternative was a harness that owned one, which would have to be closeable, which would mean this
 * factory keeping a list of every harness it ever made in order to close them -- an unbounded
 * registry to release resources that are not held.
 *
 * <p>Closing is optional: a caller that never does loses nothing, since virtual threads that finish
 * need no shutdown. A container managing this factory's lifecycle should let it.
 */
public final class DefaultDirectHarnessFactory implements DirectHarnessFactory, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DefaultDirectHarnessFactory.class);

  /**
   * Everyone who hears every agent of every harness this factory makes.
   *
   * <p>Filled after the factory exists, because a container finds its listeners once everything
   * else is built. Without this a listener an application declares can reach the queued door and
   * not this one, which is a difference nobody would predict from the outside.
   */
  private final List<NarrationListener> listeners = new CopyOnWriteArrayList<>();

  /**
   * Adds somebody who hears every agent this factory serves. Harnesses already made hear it too.
   */
  public void listener(NarrationListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
  }

  /**
   * Where events, content and the lock all come from -- held whole rather than torn into fields of
   * its own, because those stores are one decision chosen together, and a factory holding three of
   * its own fields would be exactly what let them drift apart.
   */
  private final DirectBackend backend;

  private final ProviderRegistry.Resolved providers;
  private final ProviderId defaultProvider;
  private final InferenceOptions defaultOptions;
  private final JsonSchemaGenerator schemas;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ObservationRegistry observations;
  private final List<Customizer<HarnessConfig<?>>> features;
  private final List<Customizer<DirectHarnessConfig<?>>> harnesses;

  /**
   * Where a deferring approver or tool would leave a reply address, if this door had somewhere to
   * put one waiting on it. It never does -- {@link EffectHandlers#perform} always sees {@link
   * Awaited.Deferred} become an immediate failure here -- so a token minted for this door never
   * outlives the call it was minted for, and one that does not survive a restart loses nothing. One
   * per factory rather than per harness: an address is opaque, so nothing about it is tied to a
   * particular agent type.
   */
  private final ReplyTokens replyTokens = ReplyTokens.ephemeral();

  /**
   * One virtual thread per effect, for every harness this factory makes -- see the class javadoc
   * for why it is not one each.
   */
  private final ExecutorService effects =
      ContextExecutorService.wrap(
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("nessy-direct-effect-", 0).factory()),
          ContextSnapshotFactory.builder().build());

  /**
   * Reads the config rather than holding it, so a caller that keeps a reference and changes it
   * afterwards does not change a factory that already exists.
   */
  private DefaultDirectHarnessFactory(DirectHarnessFactoryConfig config) {
    this.backend = config.requiredBackend();
    this.schemas = config.schemas();
    this.mapper = config.mapper();
    this.clock = config.clock();
    this.observations = config.observations();
    this.providers = config.providers().observed(observations);
    this.defaultProvider = config.defaultProvider();
    this.defaultOptions = config.defaultOptions();
    this.listeners.addAll(config.listeners());
    this.features = config.features();
    this.harnesses = config.harnesses();
  }

  /**
   * One factory, from every customizer that has something to say about it.
   *
   * <p>A list rather than one, because this is what a container hands over: every {@code
   * Customizer<DirectHarnessFactoryConfig>} bean an application declared, in order, each adding to
   * the same config before anything is built from it.
   */
  public static DefaultDirectHarnessFactory of(
      List<Customizer<DirectHarnessFactoryConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    DirectHarnessFactoryConfig config = new DirectHarnessFactoryConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return new DefaultDirectHarnessFactory(config);
  }

  /** One customizer, for a caller that is not a container. */
  public static DefaultDirectHarnessFactory of(Customizer<DirectHarnessFactoryConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
  }

  @Override
  public <I, O> DirectHarness<I, O> create(
      AgentType agentType, TypeRef<O> answers, Customizer<DirectHarnessConfig<I>> customizer) {
    Objects.requireNonNull(answers, "answers must not be null");
    // The same generator the tools use: turning a Java type into a JSON schema is one job, and a
    // provider constrains an answer with the same kind of document it constrains an argument with.
    AnswerShape shape = AnswerShape.of(answers.getType(), schemas, mapper);
    // Only this method knows O is what answers itself carries -- inside DefaultDirectHarness O is
    // an abstract type variable, so the parse a harness will use is decided here and handed over.
    return build(
        agentType,
        customizer,
        Optional.of(shape.schema()),
        shape.reading(OutputReader.json(mapper, answers)));
  }

  @Override
  public <I, O> DirectHarness<I, O> create(
      AgentType agentType,
      TypeRef<O> answers,
      OutputReader<O> reader,
      Customizer<DirectHarnessConfig<I>> customizer) {
    Objects.requireNonNull(reader, "reader must not be null");
    // The shape still reaches the provider: a caller supplying its own reader is saying how the
    // answer is spelled, not that it may be anything.
    AnswerShape shape = AnswerShape.of(answers.getType(), schemas, mapper);
    return build(agentType, customizer, Optional.of(shape.schema()), shape.reading(reader));
  }

  @Override
  public <I> DirectHarness<I, String> create(
      AgentType agentType, Customizer<DirectHarnessConfig<I>> customizer) {
    // Only this method knows O is String here, which is why it states its own reader rather than
    // build() stating one for both: there is nothing to parse when the shape asked for is the text.
    return build(agentType, customizer, Optional.empty(), OutputReader.text());
  }

  private <I, O> DefaultDirectHarness<I, O> build(
      AgentType agentType,
      Customizer<DirectHarnessConfig<I>> customizer,
      Optional<JsonSchema> outputSchema,
      OutputReader<O> reading) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    DefaultDirectHarnessConfig<I> config =
        new DefaultDirectHarnessConfig<>(agentType, observations, defaultProvider, defaultOptions);
    // What jars installed, then what this application says about every harness, then what this
    // caller asked for -- each able to override the one before it.
    features.forEach(feature -> feature.customize(config));
    harnesses.forEach(blanket -> blanket.customize(config));
    customizer.customize(config);
    List<ToolBinding<?>> bindings = config.bindings(schemas, mapper);
    DefaultDirectHarnessConfig.Inference inference = config.inference();
    ProviderId providerId = providers.choose(agentType, inference.provider());
    if (inference.modelName() == null) {
      throw new IllegalStateException(
          "agent type '" + agentType.value() + "' names no model and the factory has no default");
    }
    InferenceOptions options =
        new InferenceOptions(inference.modelName(), inference.maxTokens(), inference.properties());
    InferenceProvider provider = providers.resolve(agentType, providerId);
    validate(agentType, provider, options);
    if (log.isInfoEnabled()) {
      log.info(
          "NESSY INFERENCE: agent type '{}' -> {} / {}, up to {} tokens{}",
          agentType.value(),
          providerId.value(),
          options.modelName(),
          options.maxTokens(),
          propertyNames(options));
    }
    Tools tools = new Tools(bindings);
    // What each kind of effect is worth, from the tools this harness bound and the harness-wide
    // defaults alone -- exactly what the queued factory builds, so the phase-to-timeout mapping
    // lives in one place and neither door repeats it.
    EffectTermsSource terms =
        new EffectTermsSource(
            tools,
            DefaultDirectHarnessConfig.DEFAULT_TOOL_TIMEOUT,
            DefaultDirectHarnessConfig.DEFAULT_RETRY_POLICY,
            DefaultDirectHarnessConfig.DEFAULT_APPROVAL_TIMEOUT,
            DefaultDirectHarnessConfig.DEFAULT_RETRY_POLICY,
            inference.timeout(),
            inference.retryPolicy());
    inference.chapters().requireTail(agentType, inference.maxTail());
    Payloads payloads = backend.payloads();
    TurnHistories histories = ObservedTurnHistories.wrap(histories(payloads), observations);
    // The keeper hears this harness's turns end alongside whoever else the harness named.
    List<NarrationListener> own = new ArrayList<>(config.listeners());
    inference
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
    AfterCommit narrator = new AfterCommit(new Listeners(listeners, own));
    // Observed as they are handed over, the way a tool is wrapped as it is bound (§4g): what the
    // engine is given reports its own work, and the assembler knows nothing about spans. The same
    // recipe the queued factory uses, so a second implementation of it does not drift.
    InferenceContextAssembler assembler =
        ObservedInferenceContextAssembler.wrap(
            new ContextAssembler(
                histories,
                inference.chapters().on() ? backend.chapters() : null,
                inference.maxTail(),
                inference.memory().stream()
                    .map(source -> ObservedMemorySource.wrap(source, observations))
                    .toList(),
                inference.state().stream()
                    .map(source -> ObservedStateSource.wrap(source, observations))
                    .toList(),
                inference.ambient().stream()
                    .map(source -> ObservedAmbientSource.wrap(source, observations))
                    .toList()),
            observations);
    EventStreamToolCalls calls = new EventStreamToolCalls(backend.events(), payloads, agentType);
    // What performs an effect once the fold has decided one is owed -- built exactly as the
    // queued factory builds its own, so the two doors cannot describe a call, an approval or an
    // inference differently.
    EffectHandlers handlers =
        new EffectHandlers(
            new InferenceHandler(
                config.agentType(),
                new DefaultInferenceService(
                    assembler,
                    provider,
                    config.assembledSystemPrompt(),
                    tools.offers(),
                    narrator,
                    outputSchema),
                options,
                terms,
                payloads,
                narrator,
                tools),
            new ApprovalHandler(
                config.agentType(), tools, calls, replyTokens, narrator, terms, clock, payloads),
            new ToolCallHandler(config.agentType(), tools, calls, replyTokens, terms, payloads));
    return new DefaultDirectHarness<>(
        backend,
        config.agentType(),
        clock,
        config.renderer(),
        new InputLabels<>(config.agentType(), config.label()),
        reading,
        narrator,
        handlers,
        effects,
        config.maxInFlight(),
        observations,
        config.turnPolicy());
  }

  /**
   * The story, for reading. An application that shows what its agents said reads it through this
   * rather than opening the stores itself, so what it reads is what the engine wrote, decoded the
   * way the engine decodes it.
   */
  public TurnHistories histories() {
    return histories(backend.payloads());
  }

  private TurnHistories histories(Payloads payloads) {
    return (type, id) ->
        new EventStreamHistory(backend.events(), new Transcript(payloads.forAgent(id)), type, id);
  }

  /**
   * Shuts down the executor every harness's deadline enforcement runs on.
   *
   * <p>Nothing already running is interrupted -- only a deadline of its own does that -- so a
   * factory closed mid-turn lets each turn's own timeout be what ends it. Inferred by Spring as the
   * bean's destroy method, and there for anyone who built a factory without Spring.
   */
  @Override
  public void close() {
    effects.close();
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
