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
import org.jwcarman.nessy.api.event.ListenerRegistration;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.ExecutionEngine;
import org.jwcarman.nessy.spi.InProcessEngine;
import org.jwcarman.nessy.spi.Reducer;
import org.jwcarman.nessy.spi.compaction.Compactor;
import org.jwcarman.nessy.spi.compaction.Compactors;
import org.jwcarman.nessy.spi.compaction.Summarizer;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
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
  private final ListenerRegistry seededRegistry;
  private final List<ListenerRegistration> registrations = new ArrayList<>();

  private String model;
  private String systemPrompt = "";
  private int maxTokens = DEFAULT_MAX_TOKENS;
  private Set<Capability> capabilities = Set.of();
  private ToolRegistry tools = ToolRegistry.of();
  private Map<String, ToolGrant> explicitGrants;
  private Approver approver = Approver.allowAll();
  private TerminationPolicy termination = TerminationPolicy.defaults();
  private Compactor compactor;
  private Long contextWindow;
  private Consumer<ContextPipeline.Builder> contextCustomizer = pipeline -> {};
  private InputRenderer<I> renderer;

  /**
   * Seeded from a {@link Harness}: the infrastructure four (provider, store, observations, mapper)
   * come straight from the harness, with no override here; {@code defaultModel} and the harness's
   * {@link ListenerRegistry} are seeded instead, layered under whatever this builder declares
   * itself via {@link ListenerRegistry#extendedWith}.
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
    this.seededRegistry = harness.registry();
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
   * The capability and the authority to use it, declared together, per tool — the only way to
   * attach tools to this builder. The grant line is the complete security statement: every tool
   * granted here uses exactly the policy its grant carries, structurally, with no derived default
   * anywhere behind it.
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
   * Replaces the default, summarizing compactor entirely — the one compaction-related method on
   * this builder. Wins over {@link #contextWindow(long)}, whether or not that was called, since
   * there is no default left for it to feed.
   *
   * <p>The default, uncustomized compactor is assembled entirely internally — {@link
   * Summarizer#usingProvider(ModelProvider, String, int, String,
   * io.micrometer.observation.ObservationRegistry)} over the harness's provider and model, a
   * 2,048-token summary ceiling, {@link Summarizer#DEFAULT_INSTRUCTIONS}, and the harness's
   * observation registry, wrapped in {@link Compactors#summarizing(Summarizer)}'s defaults (100k
   * trigger, or a window-derived one when {@link #contextWindow(long)} is declared; the last 10
   * messages kept verbatim). To tune any of that, build a {@link Compactor} explicitly with {@link
   * Summarizer#usingProvider(ModelProvider, String, int, String,
   * io.micrometer.observation.ObservationRegistry)} and {@link Compactors#summarizing(Summarizer)}
   * and pass the result here — every knob (summary ceiling, instructions, trigger tokens, window
   * derivation, how many recent messages survive verbatim) is that builder's alone now, not this
   * method's.
   */
  public AgentBuilder<I> compaction(Compactor compactor) {
    this.compactor = Objects.requireNonNull(compactor, "compaction must not be null");
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
   * Declares a synchronous listener for this agent, after the harness's own seeded registrations,
   * in the order declared here. Frozen at {@link #build()}: no mutation path exists afterward. A
   * throw from {@code listener} propagates and stops the emitting operation — the veto is the
   * throw.
   */
  public <T> AgentBuilder<I> listen(Class<T> type, Consumer<T> listener) {
    registrations.add(ListenerRegistration.sync(type, listener));
    return this;
  }

  /**
   * Declares an asynchronous listener for this agent: {@code listener} runs on a fresh virtual
   * thread per event, and whatever it throws reaches {@code onError} instead of the emitting thread
   * — it never vetoes.
   */
  public <T> AgentBuilder<I> listenAsync(
      Class<T> type, Consumer<T> listener, Consumer<Throwable> onError) {
    registrations.add(ListenerRegistration.async(type, listener, onError));
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
        compactor != null ? compactor : assembleDefaultCompactor(resolvedModel);
    ListenerRegistry events = seededRegistry.extendedWith(registrations);
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
   * The grant map the engine consults, keyed by tool name. Only {@link #tools(ToolGrant...)}
   * populates it — there is no derivation to fall back to. A {@link #tools(ToolRegistry)} registry
   * whose tools were never granted leaves this empty, and {@code InProcessEngine}'s own
   * construction-time check ("no grant for tool: …") catches the gap as the wiring error it is.
   */
  private Map<String, ToolGrant> resolveGrants() {
    return explicitGrants != null ? explicitGrants : Map.of();
  }

  /**
   * Assembles the default, summarizing compactor entirely internally: {@link
   * Summarizer#usingProvider(ModelProvider, String, int, String,
   * io.micrometer.observation.ObservationRegistry)} over the harness's provider and {@code
   * resolvedModel}, a 2,048-token summary ceiling, and {@link Summarizer#DEFAULT_INSTRUCTIONS},
   * plus a declared {@link #contextWindow(long)}, when there is one, to derive the trigger from. No
   * window declared means {@link Compactors#summarizing}'s own default trigger (100k measured input
   * tokens) stands.
   */
  private Compactor assembleDefaultCompactor(String resolvedModel) {
    Summarizer summarizer =
        Summarizer.usingProvider(
            provider, resolvedModel, 2_048, Summarizer.DEFAULT_INSTRUCTIONS, observations);
    Compactors.SummarizingBuilder builder = Compactors.summarizing(summarizer);
    if (contextWindow != null) {
      builder = builder.window(contextWindow, maxTokens);
    }
    return builder.build();
  }
}
