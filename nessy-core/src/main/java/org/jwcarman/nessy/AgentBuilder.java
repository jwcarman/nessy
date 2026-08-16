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
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.event.ListenerRegistration;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.internal.ConversationLoop;
import org.jwcarman.nessy.internal.subagent.AgentTools;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.execute.EffectExecutors;
import org.jwcarman.nessy.spi.execute.GatedToolCallExecutor;
import org.jwcarman.nessy.spi.execute.ProviderModelCallExecutor;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.transcript.Transcript;
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
public final class AgentBuilder<I> implements ListenerDeclarations<AgentBuilder<I>> {

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentBuilder.class);

  private static final int DEFAULT_MAX_TOKENS = 4096;

  /** {@code ""} — no system prompt. */
  private static final String DEFAULT_SYSTEM_PROMPT = "";

  private final Harness harness;
  private final ModelProvider provider;
  private final ConversationStore store;
  private final boolean storeSet;
  private final Parks parks;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;
  private final String defaultModel;
  private final ListenerRegistry seededRegistry;
  private final List<ListenerRegistration> registrations = new ArrayList<>();
  private final List<SubagentConfig<String>> stringSubagentConfigs = new ArrayList<>();
  private final List<TypedSubagentDeclaration<?>> typedSubagentConfigs = new ArrayList<>();

  private String name;
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
    this.harness = harness;
    this.provider = harness.provider();
    this.store = harness.store();
    this.storeSet = harness.storeSet();
    this.parks = harness.parks();
    this.observations = harness.observations();
    this.mapper = harness.mapper();
    this.defaultModel = harness.defaultModel();
    this.seededRegistry = harness.registry();
  }

  /**
   * The agent's required identity (design §3) — a durable wire contract exactly like a queue name
   * or a callback URL, not a cosmetic label. It is how parked work finds its way home across
   * restarts: every {@link org.jwcarman.nessy.spi.conversation.Parks.Park} this agent registers is
   * stamped with it, and every callback door verifies a resolution's stamp against the agent
   * handling it before acting.
   *
   * <p>Renaming an agent with durable parks in flight orphans them — their eventual {@code
   * WrongAgentException} names the old name, and recovery means redeploying under it. Two agents
   * built from the same {@link Harness} that declare the same name are refused: every {@link
   * #build()} registers into that harness's own internal name registry, and a name already taken
   * anywhere in the harness's whole delegation tree — a sibling top-level agent, a subagent, a
   * subagent's own subagent — throws {@link IllegalArgumentException} at build time (design of
   * record 2026-08-16 §2). Two agents built from <em>different</em> harnesses can still collide at
   * run time — a stateless harness cannot detect that across harness boundaries — so avoiding it
   * there remains an application contract.
   *
   * @throws AgentConfigurationException if {@code name} is null or blank
   */
  public AgentBuilder<I> name(String name) {
    if (name == null || name.isBlank()) {
      throw new AgentConfigurationException(
          "an agent name must not be blank: it is how parked work finds its way home across"
              + " restarts, the durable stamp every callback door checks a resolution against");
    }
    this.name = name;
    return this;
  }

  /**
   * Wins over the harness's {@link HarnessBuilder#defaultModel(String)} when both are set. Neither
   * set is an {@link AgentConfigurationException} at {@link #build()}, naming the missing model.
   */
  public AgentBuilder<I> model(String model) {
    this.model = model;
    return this;
  }

  /** The system prompt sent with every model call. Default: {@link #DEFAULT_SYSTEM_PROMPT}. */
  public AgentBuilder<I> systemPrompt(String systemPrompt) {
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    return this;
  }

  /**
   * The model's per-response token ceiling, folded into {@link #build()}'s {@code ModelSettings}.
   * Default: {@link #DEFAULT_MAX_TOKENS}.
   */
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

  /**
   * The human-in-the-loop authority consulted for every tool call whose {@link UsagePolicy} is not
   * the canonical {@link UsagePolicy#allow()} singleton. Default: {@link #defaultApprover}, which
   * warns unless no approval path can exist in the first place; for the durable posture — a park
   * that survives past this process — see {@link Approver#parkAll()}.
   */
  public AgentBuilder<I> approver(Approver approver) {
    this.approver = Objects.requireNonNull(approver, "approver must not be null");
    return this;
  }

  /**
   * When the loop stops asking the model for another turn. Default: {@link
   * TerminationPolicy#defaults()}.
   */
  public AgentBuilder<I> termination(TerminationPolicy termination) {
    this.termination = Objects.requireNonNull(termination, "termination must not be null");
    return this;
  }

  /**
   * Replaces the default {@link Memory#pipeline(Transcript)} floor entirely: the content
   * jurisdiction — told every message-grade happening, asked for the finished {@link
   * org.jwcarman.nessy.api.message.Context} the loop's own {@code ModelCallExecutor} calls the
   * model with. Freedom of retention, rule of law at the border (see {@link Memory}'s own javadoc):
   * summarizing, checkpointing, or embedding memory all implement this one seam.
   */
  public AgentBuilder<I> memory(Memory memory) {
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    return this;
  }

  /**
   * Declares the model's total token budget, folded into {@link #build()}'s {@code ModelSettings}.
   * Nothing in the loop consumes this yet — it rides {@code ModelSettings} for a future compaction
   * trigger, not read anywhere today.
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
  @Override
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
  @Override
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

  /**
   * Declares a subagent over the degenerate {@code String} door (design of record 2026-08-16 §0,
   * ruling 1; §0.5): {@code customizer} fills in a {@link SubagentConfig} — {@link
   * SubagentConfig#name} and {@link SubagentConfig#description} required, everything else trimmed
   * to prompt/model/tools/memory/termination/policy — and this builder does the rest at {@link
   * #build()}: the child is constructed as an ordinary {@code Agent<String>} from this agent's own
   * harness (inheriting its provider, stores, approver, observations, and harness-seeded
   * listeners), a delegation tool naming the child is granted on this agent — its wire shape is
   * v1's {@code Delegation(String task)}, described by {@link SubagentConfig#description}, at
   * {@link SubagentConfig#policy}, default {@link UsagePolicy#allow()} — the completions wiring
   * that wakes this agent once the child settles is arranged internally, and both agents are
   * registered in the harness's own internal name registry — a name already taken anywhere in the
   * harness's whole delegation tree throws {@link IllegalArgumentException} at {@link #build()}. No
   * renderer is ever required or consulted on this door.
   *
   * <p>{@code customizer} may itself declare {@link SubagentConfig#subagent(SubagentCustomizer)} or
   * {@link SubagentConfig#subagent(Class, SubagentCustomizer)}, nesting a grandchild the same way:
   * the delegation tree is exactly the lexical nesting of these lambdas, so a cycle is
   * unrepresentable — a child is always defined inside its parent and can never refer back to it.
   *
   * @see #subagent(Class, SubagentCustomizer) the typed door
   */
  public AgentBuilder<I> subagent(SubagentCustomizer<String> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    SubagentConfig<String> config = new SubagentConfig<>();
    customizer.customize(config);
    stringSubagentConfigs.add(config);
    return this;
  }

  /**
   * Declares a subagent over the typed door (design of record 2026-08-16 §0.5): {@code inputType}
   * becomes the delegation tool's own wire shape directly — {@link
   * org.jwcarman.nessy.api.tool.Tool#inputType()} is {@code inputType}, so the model calls this
   * tool with {@code inputType}'s own victools schema, structured arguments instead of a
   * prose-packed task string — and the child is an ordinary {@code Agent<T>} opened through {@link
   * SubagentConfig#renderer}, which is REQUIRED here: {@link #build()} throws {@link
   * IllegalStateException} naming the missing renderer if {@code customizer} never calls it. No
   * silent render-as-JSON default — an explicit renderer makes a deliberate child prompt. Every
   * other piece of {@link #subagent(SubagentCustomizer)}'s own contract (harness inheritance,
   * delegation grant naming/description/policy, internal completions wiring, the internal name
   * registry's duplicate rejection, lexical nesting) applies identically here.
   *
   * @param <T> the subagent's own delegation wire shape — the record its tool arguments deserialize
   *     into
   * @see #subagent(SubagentCustomizer) the degenerate {@code String} door
   */
  public <T> AgentBuilder<I> subagent(Class<T> inputType, SubagentCustomizer<T> customizer) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    SubagentConfig<T> config = new SubagentConfig<>();
    customizer.customize(config);
    typedSubagentConfigs.add(new TypedSubagentDeclaration<>(inputType, config));
    return this;
  }

  public Agent<I> build() {
    if (name == null) {
      throw new AgentConfigurationException(
          "an agent name is required: call .name(...) before build() — the name is how parked work"
              + " finds its way home across restarts, the durable stamp every callback door checks"
              + " a resolution against");
    }
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
    Map<String, Agent<?>> childrenByName = buildSubagents();
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
                Optional.ofNullable(approver).orElseGet(() -> defaultApprover(resolvedGrants)),
                mapper,
                events,
                observations));
    ConversationLoop loop =
        new ConversationLoop(
            new ConversationLoop.Collaborators(
                executors,
                resolvedMemory,
                Optional.ofNullable(termination).orElseGet(this::defaultTermination),
                store,
                parks,
                events),
            observations,
            name);
    Agent<I> agent =
        new Agent<>(
            name,
            loop,
            events,
            store,
            new Agent.Coordination(parks, childrenByName),
            resolvedMemory,
            renderer);
    harness.subagents().register(agent);
    return agent;
  }

  /**
   * Builds every subagent {@link #subagent(SubagentCustomizer)} / {@link #subagent(Class,
   * SubagentCustomizer)} declared — string-door declarations first, then typed-door declarations,
   * in each door's own declaration order: each becomes a real child {@link Agent}, granted to this
   * builder as a delegation tool ({@link #tools} is called once more here, merging any delegation
   * grants after whatever this builder's own {@link #tools(ToolGrant...)} already declared — last
   * write wins on a name collision, the same rule {@link #tools(ToolGrant...)} already has), and
   * returns this agent's own direct children, keyed by name, for {@link Agent.Coordination}. Empty
   * when no subagent was ever declared — {@link #tools} is left untouched in that case, so a plain
   * agent's grant set is unaffected.
   *
   * <p>Every declared config, at every depth, is validated <em>before</em> any of them is built or
   * registered: a later sibling's missing name/description/renderer must never leave an earlier
   * sibling already sitting in the harness's internal registry when the throw reaches the caller —
   * the fix would otherwise be "rename it" instead of a clean rebuild.
   */
  private Map<String, Agent<?>> buildSubagents() {
    if (stringSubagentConfigs.isEmpty() && typedSubagentConfigs.isEmpty()) {
      return Map.of();
    }
    if (harness.storeSet() && !harness.subagentLinksSet()) {
      LOGGER.warn(
          "this agent declares a subagent, but the harness's own subagentLinks store was never"
              + " configured even though its session store was — every parent-child delegation"
              + " correlation lives only in memory and is lost on process exit; a child that settles"
              + " after a restart leaves its parent parked forever with nothing logged anywhere; call"
              + " .subagentLinks(...) on the harness (e.g. JdbcPersistence.subagentLinks()) to fix"
              + " it");
    }
    for (SubagentConfig<String> config : stringSubagentConfigs) {
      validateTree(config);
    }
    for (TypedSubagentDeclaration<?> declaration : typedSubagentConfigs) {
      validateTypedDeclaration(declaration);
    }
    Map<String, Agent<?>> childrenByName = new LinkedHashMap<>();
    List<ToolGrant> delegationGrants = new ArrayList<>();
    try {
      for (SubagentConfig<String> config : stringSubagentConfigs) {
        Agent<String> child = buildStringChild(config);
        childrenByName.put(config.name(), child);
        delegationGrants.add(
            ToolGrant.grant(
                AgentTools.subagent(child, config.description(), harness.subagentLinks()),
                Optional.ofNullable(config.policy()).orElseGet(UsagePolicy::allow)));
      }
      for (TypedSubagentDeclaration<?> declaration : typedSubagentConfigs) {
        delegationGrants.add(buildTypedGrant(declaration, childrenByName));
      }
    } catch (RuntimeException buildFailure) {
      // SF-5: registration-time duplicates (or any other failure) can strike partway through a
      // multi-child declaration, after earlier siblings already registered themselves inside
      // their own build(). Every sibling THIS loop directly registered — recursively cleaned of
      // its own subtree already, if that sibling's own nested build() hit this same catch — must
      // be unregistered before the throw reaches the caller, or a corrected rebuild collides on
      // one of them instead of the name that actually needs fixing.
      childrenByName.keySet().forEach(harness.subagents()::unregister);
      throw buildFailure;
    }
    List<ToolGrant> combined = new ArrayList<>();
    if (explicitGrants != null) {
      combined.addAll(explicitGrants.values());
    }
    combined.addAll(delegationGrants);
    tools(combined.toArray(new ToolGrant[0]));
    return childrenByName;
  }

  /**
   * Recursively validates {@code config} and everything nested inside it (both doors), naming
   * whichever required field is missing — the up-front pass {@link #buildSubagents()} runs before
   * building or registering anything, so a deep sibling's bad config never leaves an earlier one
   * half-registered.
   */
  private static void validateTree(SubagentConfig<?> config) {
    config.validate();
    for (SubagentConfig<String> nested : config.stringSubagents()) {
      validateTree(nested);
    }
    for (TypedSubagentDeclaration<?> nested : config.typedSubagents()) {
      validateTypedDeclaration(nested);
    }
  }

  /**
   * {@link #validateTree(SubagentConfig)} plus the typed door's own extra requirement: {@link
   * SubagentConfig#renderer} must be set, or {@link #build()} fails loudly naming the missing
   * renderer and the declaration's own {@code inputType} — no silent render-as-JSON default (design
   * of record 2026-08-16 §0.5).
   */
  private static <T> void validateTypedDeclaration(TypedSubagentDeclaration<T> declaration) {
    SubagentConfig<T> config = declaration.config();
    validateTree(config);
    if (config.renderer() == null) {
      throw new IllegalStateException(
          "a typed subagent config requires .renderer(...): '"
              + config.name()
              + "' has no InputRenderer<"
              + declaration.inputType().getSimpleName()
              + "> to reach the child agent with — the typed door never defaults one, so a"
              + " deliberate renderer must be declared explicitly");
    }
  }

  /**
   * One string-door subagent, built as an ordinary {@code String}-vocabulary {@link Agent} from
   * this builder's own harness (the same construction path {@link Harness#agent()} uses — the
   * "String door", which neither requires nor consults a renderer, unlike the typed door): inherits
   * the harness's provider, stores, approver, observations, and harness-seeded listeners; owns its
   * own name, prompt, model, tool grants, memory, and termination policy (design of record
   * 2026-08-16 §3). {@code config}'s own nested declarations recurse through this same method /
   * {@link #buildTypedChild}, so a grandchild is built and registered before this child is.
   *
   * <p>The completions listener that wakes the built agent's own parent once a further descendant
   * settles is registered here, synchronously, before {@link #build()} runs — the same sync
   * semantics {@code AgentTools.completions}'s own javadoc requires. {@code config} was already
   * validated by {@link #buildSubagents()}'s own up-front pass.
   */
  private Agent<String> buildStringChild(SubagentConfig<String> config) {
    AgentBuilder<String> childBuilder =
        new AgentBuilder<>(harness, String.class, InputRenderer.text());
    configureChild(childBuilder, config);
    return childBuilder.build();
  }

  /**
   * One typed-door subagent (design of record 2026-08-16 §0.5), built as an {@code Agent<T>}
   * through {@code config}'s own required {@link SubagentConfig#renderer} — already verified
   * present by {@link #buildSubagents()}'s up-front {@link #validateTypedDeclaration} pass, so this
   * method never itself throws for a missing renderer. Otherwise identical to {@link
   * #buildStringChild}: same harness inheritance, same recursive nesting, same synchronous
   * completions wiring.
   */
  private <T> Agent<T> buildTypedChild(TypedSubagentDeclaration<T> declaration) {
    SubagentConfig<T> config = declaration.config();
    AgentBuilder<T> childBuilder =
        new AgentBuilder<>(harness, declaration.inputType(), config.renderer());
    configureChild(childBuilder, config);
    return childBuilder.build();
  }

  /** {@link #buildTypedChild}'s own grant, paired with the child it just built. */
  private <T> ToolGrant buildTypedGrant(
      TypedSubagentDeclaration<T> declaration, Map<String, Agent<?>> childrenByName) {
    SubagentConfig<T> config = declaration.config();
    Agent<T> child = buildTypedChild(declaration);
    childrenByName.put(config.name(), child);
    return ToolGrant.grant(
        AgentTools.subagentTyped(
            child, declaration.inputType(), config.description(), harness.subagentLinks()),
        Optional.ofNullable(config.policy()).orElseGet(UsagePolicy::allow));
  }

  /**
   * The configuration common to both doors: name, model, system prompt, max tokens, memory,
   * termination, the inherited approver (design of record 2026-08-16 §3 — not a {@link
   * SubagentConfig} knob), this subagent's own tool grants, its own nested declarations (both
   * doors), and the synchronous completions listener that wakes this builder's own parent once this
   * child (or one of its own descendants) settles.
   */
  private <T> void configureChild(AgentBuilder<T> childBuilder, SubagentConfig<T> config) {
    childBuilder.name(config.name());
    if (config.model() != null) {
      childBuilder.model(config.model());
    }
    if (config.systemPrompt() != null) {
      childBuilder.systemPrompt(config.systemPrompt());
    }
    if (config.maxTokens() != null) {
      childBuilder.maxTokens(config.maxTokens());
    }
    if (config.memory() != null) {
      childBuilder.memory(config.memory());
    }
    if (config.termination() != null) {
      childBuilder.termination(config.termination());
    }
    if (approver != null) {
      // Not a SubagentConfig knob (design of record 2026-08-16 §3: the approver is inherited by
      // construction, never owned by a subagent) — copied forward from whatever this builder's own
      // approver is, cascading down through every further nesting level the same way.
      childBuilder.approver(approver);
    }
    if (!config.grants().isEmpty()) {
      childBuilder.tools(config.grants().toArray(new ToolGrant[0]));
    }
    childBuilder.stringSubagentConfigs.addAll(config.stringSubagents());
    childBuilder.typedSubagentConfigs.addAll(config.typedSubagents());
    childBuilder.listen(
        ConversationSettled.class,
        AgentTools.completions(harness.subagentLinks(), harness.parks(), harness.subagents()));
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
   * §13.1 requires this fallback to announce itself with a prominent warning — unless no approval
   * path can exist in the first place: {@code grants} empty, or every grant's {@link UsagePolicy}
   * is the canonical {@link UsagePolicy#allow()} singleton, which never consults the approver at
   * all. A custom policy stays opaque — it might defer to the approver, so its absence still warns,
   * fail-noisy for the unknown.
   */
  private Approver defaultApprover(Map<String, ToolGrant> grants) {
    boolean noApprovalPathCanExist =
        grants.values().stream().allMatch(grant -> grant.policy() == UsagePolicy.allow());
    if (!noApprovalPathCanExist) {
      LOGGER.warn(
          "no approver configured for this agent: defaulting to Approver.allowAll(), which grants"
              + " every tool call with no human in the loop; call .approver(...) to declare real"
              + " approval authority (design §13.1)");
    }
    return Approver.allowAll();
  }

  /** Error-ceiling + max-turns, {@link TerminationPolicy}'s own default. */
  private TerminationPolicy defaultTermination() {
    return TerminationPolicy.defaults();
  }

  /**
   * The floor: remembers everything verbatim through a transcript, recalls it whole. Warns when the
   * harness's own {@link ConversationStore} was explicitly configured ({@link Harness#storeSet()})
   * — a conversation persisted there will not carry its transcript across restarts unless this
   * default is overridden, the same set-vs-defaulted mismatch {@link HarnessBuilder#defaultParks()}
   * guards against for parks. Memory stays agent-scoped even so: this warns rather than auto-wiring
   * a durable transcript from the store.
   */
  private Memory defaultMemory() {
    if (storeSet) {
      LOGGER.warn(
          "no memory configured for this agent: defaulting to an in-memory pipeline Memory, even"
              + " though this harness's store was explicitly configured — a conversation persisted"
              + " there will not carry its transcript across restarts; name .memory(...) to match");
    }
    return Memory.pipeline(Transcript.inMemory()).build();
  }
}
