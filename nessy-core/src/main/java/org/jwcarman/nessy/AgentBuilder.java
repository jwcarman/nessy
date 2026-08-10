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
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.event.EventSpine;
import org.jwcarman.nessy.api.event.EventSpines;
import org.jwcarman.nessy.api.event.ListenerDeclaration;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.ExecutionEngine;
import org.jwcarman.nessy.spi.InProcessEngine;
import org.jwcarman.nessy.spi.Reducer;
import org.jwcarman.nessy.spi.compaction.Compactor;
import org.jwcarman.nessy.spi.compaction.Compactors;
import org.jwcarman.nessy.spi.compaction.Summarizer;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.TranscriptStore;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Assembles an {@link Agent}: the identity — model, system prompt, tools, policies — layered on top
 * of a {@link Harness}'s shared infrastructure.
 *
 * <p>Disjoint from {@link HarnessBuilder} by design (design §17's razor): the provider, session
 * store, observation registry, and object mapper are the harness's alone, never overridable here.
 * Only the model is seeded rather than owned outright — {@link #model(String)} wins over the
 * harness's {@link HarnessBuilder#defaultModel(String)}, and neither supplied is an {@link
 * AgentConfigurationException} at {@link #build()}. Instances come from {@link Harness#agent()} /
 * {@link Harness#agent(Class)}, never from a public constructor.
 *
 * @param <I> the input vocabulary the built {@link Agent} will accept via {@code tell}
 */
public final class AgentBuilder<I> {

  private static final int DEFAULT_MAX_TOKENS = 4096;

  private final Class<I> vocabulary;
  private final ModelProvider provider;
  private final ConversationStore store;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;
  private final String defaultModel;
  private final List<ListenerDeclaration> seededDeclarations;
  private final List<ListenerDeclaration> declarations = new ArrayList<>();

  private String model;
  private String systemPrompt = "";
  private int maxTokens = DEFAULT_MAX_TOKENS;
  private Set<Capability> capabilities = Set.of();
  private ToolRegistry tools = ToolRegistry.of();
  private Map<String, ToolGrant> explicitGrants;
  private Approver approver = Approver.allowAll();
  private TerminationPolicy termination = TerminationPolicy.defaults();
  private Compactor compactor;
  private Summarizer summarizer;
  private int summaryMaxTokens = 2_048;
  private String summaryInstructions = Summarizer.DEFAULT_INSTRUCTIONS;
  private Long contextWindow;
  private Consumer<ContextPipeline.Builder> contextCustomizer = pipeline -> {};
  private TranscriptStore transcript;
  private InputRenderer<I> renderer;

  /**
   * Seeded from a {@link Harness}: the infrastructure four (provider, store, observations, mapper)
   * come straight from the harness, with no override here; {@code defaultModel} and the harness's
   * declared listeners are seeded instead, layered under whatever this builder declares itself.
   *
   * <p>{@code defaultRenderer} is the vocabulary-driven default — {@link InputRenderer#text()} for
   * {@code String}, {@link InputRenderer#json(ObjectMapper)} over the harness mapper otherwise —
   * chosen by the caller ({@link Harness#agent()} / {@link Harness#agent(Class)}) so that no
   * unchecked cast is ever needed here; {@link #renderer(InputRenderer)} overrides it.
   */
  AgentBuilder(Harness harness, Class<I> vocabulary, InputRenderer<I> defaultRenderer) {
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
    this.renderer = Objects.requireNonNull(defaultRenderer, "defaultRenderer must not be null");
    this.provider = harness.provider();
    this.store = harness.store();
    this.observations = harness.observations();
    this.mapper = harness.mapper();
    this.defaultModel = harness.defaultModel();
    this.seededDeclarations = harness.declarations();
  }

  /**
   * Wins over the harness's {@link HarnessBuilder#defaultModel(String)} when both are set. Neither
   * set is an {@link AgentConfigurationException} at {@link #build()}, naming the missing model.
   */
  public AgentBuilder<I> model(String model) {
    this.model = model;
    return this;
  }

  public AgentBuilder<I> systemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
    return this;
  }

  public AgentBuilder<I> maxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
    return this;
  }

  /** What this agent asks providers to use. Empty means "whatever the provider does by default". */
  public AgentBuilder<I> capabilities(Set<Capability> capabilities) {
    this.capabilities = Set.copyOf(capabilities);
    return this;
  }

  public AgentBuilder<I> tools(ToolRegistry tools) {
    this.tools = tools;
    this.explicitGrants = null;
    return this;
  }

  /**
   * Sugar for the common case: a handful of tools, no registry to assemble by hand. Each tool is
   * auto-wrapped via {@link ToolGrant#grant(Tool)} — the derived default, unchanged from how
   * approval worked before grants existed.
   */
  public AgentBuilder<I> tools(Tool<?>... tools) {
    this.tools = ToolRegistry.of(tools);
    this.explicitGrants = null;
    return this;
  }

  /**
   * The capability and the authority to use it, declared together, per tool. Supersedes {@link
   * #tools(Tool...)} for the same builder: the grant line is the security statement, and every tool
   * granted here uses exactly the policy its grant carries rather than a derived default.
   */
  public AgentBuilder<I> tools(ToolGrant... grants) {
    Objects.requireNonNull(grants, "grants must not be null");
    Tool<?>[] granted = new Tool<?>[grants.length];
    Map<String, ToolGrant> byName = new LinkedHashMap<>();
    for (int i = 0; i < grants.length; i++) {
      Objects.requireNonNull(grants[i], "grants[" + i + "] must not be null");
      granted[i] = grants[i].tool();
      byName.put(grants[i].tool().name(), grants[i]);
    }
    this.tools = ToolRegistry.of(granted);
    this.explicitGrants = byName;
    return this;
  }

  public AgentBuilder<I> approver(Approver approver) {
    this.approver = approver;
    return this;
  }

  public AgentBuilder<I> termination(TerminationPolicy termination) {
    this.termination = termination;
    return this;
  }

  /**
   * Replaces the default, summarizing compactor entirely — the one overload compaction has. Wins
   * over {@link #summarizer(Summarizer)} and {@link #contextWindow(long)}, whether or not those
   * were called, since there is no default left for them to feed.
   *
   * <p>To tune the built-in summarizing default instead of replacing it, build one explicitly with
   * {@link Compactors#summarizing(Summarizer)} and pass the result here — the knobs it exposes
   * (trigger tokens, window derivation, how many recent messages survive verbatim) each belong to
   * that builder now, not to this method.
   */
  public AgentBuilder<I> compaction(Compactor compactor) {
    this.compactor = Objects.requireNonNull(compactor, "compaction must not be null");
    return this;
  }

  /**
   * What performs the default summarizing compactor's model call. Default: {@link
   * Summarizer#usingProvider(ModelProvider, ModelSettings, int, String,
   * io.micrometer.observation.ObservationRegistry)} over the harness's provider, settings, {@link
   * #summaryMaxTokens(int)}, {@link #summaryInstructions(String)}, and the harness's observation
   * registry. Wins over both of those knobs — an explicit summarizer needs nothing this builder
   * would otherwise bake in for it. Ignored when {@link #compaction(Compactor)} is called.
   */
  public AgentBuilder<I> summarizer(Summarizer summarizer) {
    this.summarizer = summarizer;
    return this;
  }

  /**
   * Caps the default summarizer's own reply. Default 2,048. Ignored once {@link
   * #summarizer(Summarizer)} or {@link #compaction(Compactor)} replaces the default summarizer
   * entirely.
   */
  public AgentBuilder<I> summaryMaxTokens(int summaryMaxTokens) {
    if (summaryMaxTokens < 1) {
      throw new IllegalArgumentException("summaryMaxTokens must be at least 1");
    }
    this.summaryMaxTokens = summaryMaxTokens;
    return this;
  }

  /**
   * What the default summarizer asks the model for. Default {@link
   * Summarizer#DEFAULT_INSTRUCTIONS}. Ignored once {@link #summarizer(Summarizer)} or {@link
   * #compaction(Compactor)} replaces the default summarizer entirely.
   */
  public AgentBuilder<I> summaryInstructions(String summaryInstructions) {
    this.summaryInstructions =
        Objects.requireNonNull(summaryInstructions, "summaryInstructions must not be null");
    return this;
  }

  /**
   * Declares the model's total token budget. When set and {@link #compaction(Compactor)} is never
   * called, {@link #build()} derives the default compactor's trigger from it via {@link
   * Compactors.SummarizingBuilder#window}; an explicit {@code compaction(...)} call always wins.
   */
  public AgentBuilder<I> contextWindow(long contextWindow) {
    this.contextWindow = contextWindow;
    return this;
  }

  /**
   * Declares this agent's Contextualize phase (§6.1): the ordered {@code project} transforms and
   * {@code enrich} contributors a {@link ContextPipeline} runs to turn ledger into {@link
   * org.jwcarman.nessy.api.message.Context} for one conversational call, plus where enriched
   * material lands relative to the projected transcript. Declared once, at build time, in
   * reviewable code — the one fully-open phase, but still closed to runtime registration.
   *
   * <p>Default: no projections, no enrichers, {@link ContextPipeline.Placement#ENRICHMENTS_FIRST} —
   * the model sees the full working set unchanged, scoped to this one agent, never a harness-level
   * default.
   *
   * <pre>{@code
   * builder.context(pipeline -> pipeline
   *     .project(ctx -> ctx.elideToolResults(2))
   *     .enrich(graphMemory)
   *     .placement(ContextPipeline.Placement.ENRICHMENTS_FIRST));
   * }</pre>
   */
  public AgentBuilder<I> context(Consumer<ContextPipeline.Builder> customizer) {
    this.contextCustomizer = Objects.requireNonNull(customizer, "customizer must not be null");
    return this;
  }

  /**
   * Declares a synchronous listener for this agent, after the harness's own seeded declarations, in
   * the order declared here. Frozen at {@link #build()}: no mutation path exists afterward. A throw
   * from {@code listener} propagates and stops the emitting operation — the veto is the throw.
   */
  public <T> AgentBuilder<I> listen(Class<T> type, Consumer<T> listener) {
    declarations.add(ListenerDeclaration.sync(type, listener));
    return this;
  }

  /**
   * Declares an asynchronous listener for this agent: {@code listener} runs on a fresh virtual
   * thread per event, and whatever it throws reaches {@code onError} instead of the emitting thread
   * — it never vetoes.
   */
  public <T> AgentBuilder<I> listenAsync(
      Class<T> type, Consumer<T> listener, Consumer<Throwable> onError) {
    declarations.add(ListenerDeclaration.async(type, listener, onError));
    return this;
  }

  /**
   * {@link #listenAsync(Class, Consumer, Consumer)}, reporting a failed listener to a JDK {@link
   * System.Logger} rather than requiring every caller to supply its own handler.
   */
  public <T> AgentBuilder<I> listenAsync(Class<T> type, Consumer<T> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    System.Logger logger = System.getLogger(AgentBuilder.class.getName());
    return listenAsync(
        type, listener, t -> logger.log(Level.ERROR, "async event listener failed", t));
  }

  /**
   * Sugar for this one agent: wires {@code transcript} as an inline, synchronous {@link
   * org.jwcarman.nessy.api.event.MessageAppended} listener at {@link #build()}. Unusual: {@link
   * HarnessBuilder#transcript(TranscriptStore)} is the normal home for a journal every agent
   * shares, registered once at harness build time; reach for this only when one particular agent
   * needs its own, separate journal. Default: none — retention is a deliberate declaration, not a
   * silent default.
   */
  public AgentBuilder<I> transcript(TranscriptStore transcript) {
    this.transcript = transcript;
    return this;
  }

  /**
   * Overrides the vocabulary-driven default renderer: {@link InputRenderer#text()} for a {@code
   * String} vocabulary, {@link InputRenderer#json(ObjectMapper)} over the harness mapper otherwise.
   * The sealed-switch renderer over an application's own sealed input vocabulary is the recommended
   * idiom for anything richer than tagged JSON.
   */
  public AgentBuilder<I> renderer(InputRenderer<I> renderer) {
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    return this;
  }

  public Agent<I> build() {
    String resolvedModel = (model != null && !model.isBlank()) ? model : defaultModel;
    if (resolvedModel == null || resolvedModel.isBlank()) {
      throw new AgentConfigurationException(
          "a model name is required: call model(...) on the agent, or defaultModel(...) on the"
              + " harness");
    }
    ModelSettings settings =
        new ModelSettings(resolvedModel, systemPrompt, maxTokens, capabilities, contextWindow);
    Compactor resolvedCompactor =
        compactor != null ? compactor : assembleDefaultCompactor(settings);
    EventSpine events = EventSpines.of(frozenDeclarations());
    // Constructed once here and handed to both the engine and the Agent: the invariant is one
    // ContextPipeline instance per agent, so requestFor and contextFor never disagree about what
    // a call sees.
    ContextPipeline.Builder pipelineBuilder = ContextPipeline.builder();
    contextCustomizer.accept(pipelineBuilder);
    ContextPipeline contextPipeline = pipelineBuilder.build(events, observations);
    ExecutionEngine engine =
        new InProcessEngine(
            provider,
            tools,
            resolveGrants(),
            approver,
            store,
            events,
            new Reducer(termination, resolvedCompactor),
            settings,
            mapper,
            observations,
            contextPipeline);
    return new Agent<>(engine, events, store, contextPipeline, renderer);
  }

  /**
   * The harness's declarations first, in order, then this builder's own — the seeded-provider
   * pattern applied to listeners (design §17) — then this agent's own {@link #transcript} sugar
   * last, if declared.
   */
  private List<ListenerDeclaration> frozenDeclarations() {
    List<ListenerDeclaration> frozen =
        new ArrayList<>(seededDeclarations.size() + declarations.size() + 1);
    frozen.addAll(seededDeclarations);
    frozen.addAll(declarations);
    if (transcript != null) {
      frozen.add(transcript.declareListener());
    }
    return frozen;
  }

  /**
   * The grant map the engine consults, keyed by tool name. Explicit grants from {@link
   * #tools(ToolGrant...)} win outright; otherwise every tool in {@link #tools} — however it was
   * set, including a hand-rolled {@link ToolRegistry} — gets {@link ToolGrant#grant(Tool)}'s
   * derived default, so an agent that never mentions grants behaves exactly as it did before grants
   * existed.
   */
  private Map<String, ToolGrant> resolveGrants() {
    if (explicitGrants != null) {
      return explicitGrants;
    }
    Map<String, ToolGrant> derived = new LinkedHashMap<>();
    for (ToolSpec spec : tools.specs()) {
      tools.find(spec.name()).ifPresent(tool -> derived.put(spec.name(), ToolGrant.grant(tool)));
    }
    return derived;
  }

  /**
   * Assembles the default, summarizing compactor from {@link #summarizer(Summarizer)} (or {@link
   * Summarizer#usingProvider(ModelProvider, ModelSettings, int, String,
   * io.micrometer.observation.ObservationRegistry)} over the harness's provider, settings, {@link
   * #summaryMaxTokens(int)}, {@link #summaryInstructions(String)}, and the harness's observation
   * registry, by default) and a declared {@link #contextWindow(long)}, when there is one, to derive
   * the trigger from. No window declared means the builder's own default trigger (100k measured
   * input tokens) stands.
   */
  private Compactor assembleDefaultCompactor(ModelSettings settings) {
    Summarizer resolvedSummarizer =
        summarizer != null
            ? summarizer
            : Summarizer.usingProvider(
                provider, settings, summaryMaxTokens, summaryInstructions, observations);
    Compactors.SummarizingBuilder builder = Compactors.summarizing(resolvedSummarizer);
    if (contextWindow != null) {
      builder = builder.window(contextWindow, maxTokens);
    }
    return builder.build();
  }
}
