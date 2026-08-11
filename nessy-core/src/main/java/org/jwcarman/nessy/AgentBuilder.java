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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.jwcarman.nessy.internal.ConversationLoop;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.execute.EffectExecutors;
import org.jwcarman.nessy.spi.execute.GatedToolCallExecutor;
import org.jwcarman.nessy.spi.execute.ProviderModelCallExecutor;
import org.jwcarman.nessy.spi.memory.ListMemory;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentBuilder.class);

  private static final int DEFAULT_MAX_TOKENS = 4096;

  /** {@code ""} — no system prompt. */
  private static final String DEFAULT_SYSTEM_PROMPT = "";

  private final ModelProvider provider;
  private final ConversationStore store;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;
  private final String defaultModel;
  private final ListenerRegistry seededRegistry;
  private final List<ListenerRegistration> registrations = new ArrayList<>();

  private String model;
  private String systemPrompt;
  private Integer maxTokens;
  private Set<Capability> capabilities;
  private ToolRegistry tools;
  private Map<String, ToolGrant> explicitGrants;
  private Approver approver;
  private TerminationPolicy termination;
  private Memory memory;
  private Long contextWindow;
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
    Objects.requireNonNull(vocabulary, "vocabulary must not be null");
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

  /**
   * The capability and the authority to use it, declared together, per tool. The grant line is the
   * complete security statement: every tool granted here uses exactly the policy its grant carries,
   * structurally, with no derived default anywhere behind it.
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
   * Replaces the default {@link ListMemory} floor entirely: the content jurisdiction — told every
   * message-grade happening, asked for the finished {@link org.jwcarman.nessy.api.message.Context}
   * the loop's own {@code ModelCallExecutor} calls the model with. Freedom of retention, rule of
   * law at the border (see {@link Memory}'s own javadoc): summarizing, checkpointing, or embedding
   * memory all implement this one seam.
   */
  public AgentBuilder<I> memory(Memory memory) {
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    return this;
  }

  /**
   * Declares the model's total token budget, folded into {@link #build()}'s {@code ModelSettings}.
   */
  public AgentBuilder<I> contextWindow(long contextWindow) {
    this.contextWindow = contextWindow;
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
   * {@link #listenAsync(Class, Consumer, Consumer)}, reporting a failed listener to an SLF4J {@link
   * Logger} rather than requiring every caller to supply its own handler.
   */
  public <T> AgentBuilder<I> listenAsync(Class<T> type, Consumer<T> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    return listenAsync(type, listener, t -> LOGGER.error("async event listener failed", t));
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
    int resolvedMaxTokens = Optional.ofNullable(maxTokens).orElseGet(this::defaultMaxTokens);
    ModelSettings settings =
        new ModelSettings(
            resolvedModel,
            Optional.ofNullable(systemPrompt).orElse(DEFAULT_SYSTEM_PROMPT),
            resolvedMaxTokens,
            Optional.ofNullable(capabilities).orElseGet(this::defaultCapabilities),
            contextWindow);
    ListenerRegistry events = seededRegistry.extendedWith(registrations);
    ToolRegistry resolvedTools = Optional.ofNullable(tools).orElseGet(this::defaultTools);
    Map<String, ToolGrant> resolvedGrants =
        Optional.ofNullable(explicitGrants).orElseGet(this::defaultGrants);
    // Constructed once here and handed to both the model-call executor and the Agent: the
    // invariant is one Memory instance per agent, so the wire request and contextFor never
    // disagree about what a call sees.
    Memory resolvedMemory = Optional.ofNullable(memory).orElseGet(this::defaultMemory);
    EffectExecutors executors =
        new EffectExecutors(
            new ProviderModelCallExecutor(
                provider, settings, resolvedTools, resolvedMemory, observations),
            new GatedToolCallExecutor(
                resolvedTools,
                resolvedGrants,
                Optional.ofNullable(approver).orElseGet(this::defaultApprover),
                mapper,
                events,
                observations));
    ConversationLoop loop =
        new ConversationLoop(
            executors,
            resolvedMemory,
            Optional.ofNullable(termination).orElseGet(this::defaultTermination),
            store,
            events,
            observations);
    return new Agent<>(loop, events, store, resolvedMemory, renderer);
  }

  /** {@link #DEFAULT_MAX_TOKENS}. */
  private int defaultMaxTokens() {
    return DEFAULT_MAX_TOKENS;
  }

  /** No capabilities requested — "whatever the provider does by default". */
  private Set<Capability> defaultCapabilities() {
    return Set.of();
  }

  /** No tools attached. */
  private ToolRegistry defaultTools() {
    return ToolRegistry.of();
  }

  /**
   * The grant map the loop's tool-call executor consults, keyed by tool name. Only {@link
   * #tools(ToolGrant...)} populates {@link #explicitGrants} — there is no derivation to fall back
   * to, so the default is simply empty.
   */
  private Map<String, ToolGrant> defaultGrants() {
    return Map.of();
  }

  /**
   * {@link Approver#allowAll()} — every tool call is granted with no human in the loop. Design
   * §13.1 requires this fallback to announce itself with a prominent warning, so it does, once per
   * agent {@link #build()}: approval authority is always the application's own explicit
   * declaration, never a silent default for anything a real tool grant deserves.
   */
  private Approver defaultApprover() {
    LOGGER.warn(
        "no approver configured for this agent: defaulting to Approver.allowAll(), which grants"
            + " every tool call with no human in the loop; call .approver(...) to declare real"
            + " approval authority (design §13.1)");
    return Approver.allowAll();
  }

  /** Error-ceiling + max-turns, {@link TerminationPolicy}'s own default. */
  private TerminationPolicy defaultTermination() {
    return TerminationPolicy.defaults();
  }

  /** The floor: remembers everything verbatim, recalls it whole. */
  private Memory defaultMemory() {
    return new ListMemory();
  }
}
