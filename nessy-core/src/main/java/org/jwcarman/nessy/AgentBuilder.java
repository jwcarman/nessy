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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.CompactionStrategy;
import org.jwcarman.nessy.api.CompactionTrigger;
import org.jwcarman.nessy.api.TerminationPolicy;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.ExecutionEngine;
import org.jwcarman.nessy.spi.InProcessEngine;
import org.jwcarman.nessy.spi.Reducer;
import org.jwcarman.nessy.spi.compaction.CompactionStrategies;
import org.jwcarman.nessy.spi.compaction.Summarizer;
import org.jwcarman.nessy.spi.context.ContextBuilder;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.session.SessionStore;
import org.jwcarman.nessy.spi.session.TranscriptStore;

/**
 * Assembles an {@link Agent}: the identity — model, system prompt, tools, policies — layered on top
 * of a {@link Harness}'s shared infrastructure.
 *
 * <p>Everything except the model has a default that works, so the smallest useful agent is a
 * provider and a model name. Every default here is a seam you can replace, which is the whole point
 * of the framework. Instances come from {@link Harness#agent()} (or, for the common single-agent
 * case, {@link Nessy#agent()}), never from a public constructor.
 */
public final class AgentBuilder {

  private static final int DEFAULT_MAX_TOKENS = 4096;

  private ModelProvider provider;
  private String model;
  private String systemPrompt = "";
  private int maxTokens = DEFAULT_MAX_TOKENS;
  private Set<Capability> capabilities = Set.of();
  private ToolRegistry tools = ToolRegistry.of();
  private Map<String, ToolGrant> explicitGrants;
  private Approver approver = Approver.allowAll();
  private SessionStore store;
  private EventHub events;
  private TerminationPolicy termination = TerminationPolicy.defaults();
  private CompactionPolicy compaction = CompactionPolicy.defaults();
  private boolean compactionExplicit;
  private CompactionStrategy compactionStrategy;
  private Summarizer summarizer;
  private Long contextWindow;
  private ObjectMapper mapper;
  private ObservationRegistry observations;
  private ContextBuilder contextBuilder = ContextBuilder.identity();
  private TranscriptStore transcript;
  private Memory memory = Memory.none();

  /**
   * Seeded from a {@link Harness}: the infrastructure six start out at the harness's values, and
   * this builder layers identity — model, system prompt, tools, policies — on top. Every infra
   * setter below (store, transcript, events, mapper, observations) is an escape hatch that
   * overrides the harness for this one agent; the harness is the normal home for those values.
   */
  AgentBuilder(Harness harness) {
    this.provider = harness.provider();
    this.store = harness.store();
    this.transcript = harness.transcript();
    this.events = harness.hub();
    this.observations = harness.observations();
    this.mapper = harness.mapper();
  }

  /**
   * Overrides the harness's default model provider for this one agent. Unusual: the harness is the
   * normal home for the provider every agent shares; reach for this only when a particular agent
   * genuinely needs a different model line.
   */
  public AgentBuilder provider(ModelProvider provider) {
    this.provider = provider;
    return this;
  }

  public AgentBuilder model(String model) {
    this.model = model;
    return this;
  }

  public AgentBuilder systemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
    return this;
  }

  public AgentBuilder maxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
    return this;
  }

  /** What this agent asks providers to use. Empty means "whatever the provider does by default". */
  public AgentBuilder capabilities(Set<Capability> capabilities) {
    this.capabilities = Set.copyOf(capabilities);
    return this;
  }

  public AgentBuilder tools(ToolRegistry tools) {
    this.tools = tools;
    this.explicitGrants = null;
    return this;
  }

  /**
   * Sugar for the common case: a handful of tools, no registry to assemble by hand. Each tool is
   * auto-wrapped via {@link ToolGrant#grant(Tool)} — the derived default, unchanged from how
   * approval worked before grants existed.
   */
  public AgentBuilder tools(Tool<?>... tools) {
    this.tools = ToolRegistry.of(tools);
    this.explicitGrants = null;
    return this;
  }

  /**
   * The capability and the authority to use it, declared together, per tool. Supersedes {@link
   * #tools(Tool...)} for the same builder: the grant line is the security statement, and every tool
   * granted here uses exactly the policy its grant carries rather than a derived default.
   */
  public AgentBuilder tools(ToolGrant... grants) {
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

  public AgentBuilder approver(Approver approver) {
    this.approver = approver;
    return this;
  }

  /**
   * Overrides the harness's session store for this one agent. Unusual: the harness is the normal
   * home for the store every agent shares.
   */
  public AgentBuilder store(SessionStore store) {
    this.store = store;
    return this;
  }

  /**
   * Overrides the harness's event hub for this one agent. Unusual: the harness is the normal home
   * for the hub every agent shares.
   */
  public AgentBuilder events(EventHub events) {
    this.events = events;
    return this;
  }

  public AgentBuilder termination(TerminationPolicy termination) {
    this.termination = termination;
    return this;
  }

  /**
   * Tunes the default, summarizing strategy: when it triggers, how much it keeps verbatim, and what
   * it asks the summarizer for. Superseded entirely by {@link #compaction(CompactionStrategy)},
   * explicit or not — that overload replaces the strategy outright, so there is nothing here left
   * to tune.
   */
  public AgentBuilder compaction(CompactionPolicy compaction) {
    this.compaction = compaction;
    this.compactionExplicit = true;
    return this;
  }

  /**
   * Escapes the built-in summarizing strategy entirely. Wins over {@link
   * #compaction(CompactionPolicy)}, {@link #summarizer(Summarizer)}, and {@link
   * #contextWindow(long)}, whether or not those were called.
   */
  public AgentBuilder compaction(CompactionStrategy compaction) {
    this.compactionStrategy = compaction;
    return this;
  }

  /**
   * What performs the default summarizing strategy's model call. Default: {@link
   * Summarizer#usingProvider} over this builder's {@link #provider(ModelProvider)} and settings.
   * Ignored when {@link #compaction(CompactionStrategy)} is called.
   */
  public AgentBuilder summarizer(Summarizer summarizer) {
    this.summarizer = summarizer;
    return this;
  }

  /**
   * Declares the model's total token budget. When set and {@link #compaction(CompactionPolicy)} is
   * never called, {@link #build()} derives the compaction trigger from it via {@link
   * CompactionTrigger#forWindow}; an explicit {@code compaction(...)} call always wins.
   */
  public AgentBuilder contextWindow(long contextWindow) {
    this.contextWindow = contextWindow;
    return this;
  }

  /**
   * What a conversational call sees, projected from the full session state. Default: everything.
   */
  public AgentBuilder contextBuilder(ContextBuilder contextBuilder) {
    this.contextBuilder = contextBuilder;
    return this;
  }

  /**
   * What recalls messages from outside this session's own transcript ahead of each conversational
   * request. Default: {@link Memory#none()} — recall is identity, scoped to this one agent, never a
   * harness-level default the way {@link #store(SessionStore)} or {@link #events(EventHub)} are.
   */
  public AgentBuilder memory(Memory memory) {
    this.memory = memory;
    return this;
  }

  /**
   * Overrides the harness's Jackson mapper for this one agent. Unusual: the harness is the normal
   * home for the mapper every agent shares.
   */
  public AgentBuilder objectMapper(ObjectMapper mapper) {
    this.mapper = mapper;
    return this;
  }

  /**
   * Overrides the harness's observation registry for this one agent. Unusual: the harness is the
   * normal home for the registry every agent shares.
   */
  public AgentBuilder observations(ObservationRegistry observations) {
    this.observations = observations;
    return this;
  }

  /**
   * Overrides the harness's transcript store for this one agent. Unusual: the harness is the normal
   * home for the transcript every agent shares. Harness default: {@link TranscriptStore#none()} —
   * retention is a deliberate declaration, not a silent default.
   */
  public AgentBuilder transcript(TranscriptStore transcript) {
    this.transcript = transcript;
    return this;
  }

  public Agent build() {
    if (provider == null) {
      throw new IllegalStateException("a model provider is required: call provider(...)");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalStateException("a model name is required: call model(...)");
    }
    ModelSettings settings =
        new ModelSettings(model, systemPrompt, maxTokens, capabilities, contextWindow);
    CompactionStrategy resolvedStrategy =
        compactionStrategy != null ? compactionStrategy : assembleCompactionStrategy(settings);
    ExecutionEngine engine =
        new InProcessEngine(
            provider,
            tools,
            resolveGrants(),
            approver,
            store,
            events,
            new Reducer(termination, resolvedStrategy),
            settings,
            mapper,
            observations,
            contextBuilder,
            transcript,
            memory);
    return new Agent(engine, events);
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
   * Assembles the default, summarizing strategy from {@link #compaction(CompactionPolicy)} (or a
   * window-derived trigger, per {@link #contextWindow(long)}) and {@link #summarizer(Summarizer)}
   * (or {@link Summarizer#usingProvider} over this builder's provider, by default). A resolved
   * policy equal to {@link CompactionPolicy#disabled()} short-circuits to {@link
   * CompactionStrategy#disabled()} rather than wrapping a summarizer that would never run.
   */
  private CompactionStrategy assembleCompactionStrategy(ModelSettings settings) {
    Objects.requireNonNull(compaction, "compaction must not be null");
    CompactionPolicy resolvedPolicy =
        contextWindow != null && !compactionExplicit
            ? new CompactionPolicy(
                CompactionTrigger.forWindow(contextWindow, maxTokens),
                compaction.keepRecentMessages(),
                compaction.summaryMaxTokens(),
                compaction.instructions())
            : compaction;
    if (resolvedPolicy.equals(CompactionPolicy.disabled())) {
      return CompactionStrategy.disabled();
    }
    Summarizer resolvedSummarizer =
        summarizer != null ? summarizer : Summarizer.usingProvider(provider, settings);
    return CompactionStrategies.summarizing(resolvedPolicy, resolvedSummarizer);
  }
}
