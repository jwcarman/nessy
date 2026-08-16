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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.event.ListenerRegistration;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.transcript.Transcript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What an application writes to describe an {@link Agent}, handed to {@link
 * Harness#agent(AgentCustomizer)} / {@link Harness#agent(Class, AgentCustomizer)}: a CONFIG, not a
 * builder (design of record 2026-08-16 §1) — fluent setters, no {@code build()}. The identity —
 * model, system prompt, tools, policies — layered on top of a {@link Harness}'s shared
 * infrastructure.
 *
 * <p>Disjoint from {@link HarnessConfig} by design (design §17's razor): the provider, session
 * store, observation registry, and object mapper are the harness's alone, never overridable here.
 * Only the model is seeded rather than owned outright — {@link #model(String)} wins over the
 * harness's {@link HarnessConfig#defaultModel(String)}, and neither supplied is an {@link
 * AgentConfigurationException} at the agent factory. Instances come from {@link
 * Harness#agent(AgentCustomizer)} / {@link Harness#agent(Class, AgentCustomizer)}, never from a
 * public constructor. The actual assembly of the finished {@link Agent} — folding this config's
 * resolved fields together with the harness's own collaborators — is {@link AgentAssembly}'s job,
 * not this class's own (java:S6539: keeping that heavier machinery out of this class is what keeps
 * this class's own coupling in check).
 *
 * @param <T> the input vocabulary the built {@link Agent} will accept via {@code tell}
 */
public final class AgentConfig<T> implements ListenerDeclarations<AgentConfig<T>> {

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentConfig.class);

  private final Harness harness;
  private final List<ListenerRegistration> registrations = new ArrayList<>();
  private final SubagentAssembly subagentAssembly = new SubagentAssembly(this);

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
  private InputRenderer<T> renderer;
  private Function<ConversationId, ?> principalResolver;
  private Class<?> intentType;

  /**
   * Seeded from a {@link Harness}: {@code inputType} is only used here to fail fast on a null
   * vocabulary token; type agreement between it, {@code defaultRenderer}, and every subsequent
   * {@link #renderer(InputRenderer)} override is closed by the compiler unifying {@code T} across
   * the caller's own {@link Harness#agent(AgentCustomizer)} / {@link Harness#agent(Class,
   * AgentCustomizer)} call, this config, and the returned {@code Agent<T>} — nothing here needs to
   * check it again at runtime. {@code defaultRenderer} is chosen by that same caller so that no
   * unchecked cast is ever needed here; {@link #renderer(InputRenderer)} overrides it.
   */
  AgentConfig(Harness harness, Class<T> inputType, InputRenderer<T> defaultRenderer) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    this.renderer = Objects.requireNonNull(defaultRenderer, "renderer must not be null");
    this.harness = harness;
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
   * built from the same {@link Harness} that declare the same name are refused: every build
   * registers into that harness's own internal name registry, and a name already taken anywhere in
   * the harness's whole delegation tree — a sibling top-level agent, a subagent, a subagent's own
   * subagent — throws {@link IllegalArgumentException} at build time (design of record 2026-08-16
   * §2). Two agents built from <em>different</em> harnesses can still collide at run time — a
   * stateless harness cannot detect that across harness boundaries — so avoiding it there remains
   * an application contract.
   *
   * @throws AgentConfigurationException if {@code name} is null or blank
   */
  public AgentConfig<T> name(String name) {
    if (name == null || name.isBlank()) {
      throw new AgentConfigurationException(
          "an agent name must not be blank: it is how parked work finds its way home across"
              + " restarts, the durable stamp every callback door checks a resolution against");
    }
    this.name = name;
    return this;
  }

  /**
   * Wins over the harness's {@link HarnessConfig#defaultModel(String)} when both are set. Neither
   * set throws an {@link AgentConfigurationException} naming the missing model.
   */
  public AgentConfig<T> model(String model) {
    this.model = model;
    return this;
  }

  /** The system prompt sent with every model call. Default: {@code ""} (no system prompt). */
  public AgentConfig<T> systemPrompt(String systemPrompt) {
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    return this;
  }

  /**
   * The model's per-response token ceiling, folded into the built agent's {@code ModelSettings}.
   * Default: {@link AgentAssembly}'s own default.
   */
  public AgentConfig<T> maxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
    return this;
  }

  /** What this agent asks providers to use. Empty means "whatever the provider does by default". */
  public AgentConfig<T> capabilities(Set<Capability> capabilities) {
    this.capabilities = Set.copyOf(capabilities);
    return this;
  }

  /**
   * The capability and the authority to use it, declared together, per tool. The grant line is the
   * complete security statement: every tool granted here uses exactly the policy its grant carries,
   * structurally, with no derived default anywhere behind it.
   */
  public AgentConfig<T> tools(ToolGrant... grants) {
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
   * the canonical {@link UsagePolicy#allow()} singleton. Default: {@link AgentAssembly}'s own
   * default, which warns unless no approval path can exist in the first place; for the durable
   * posture — a park that survives past this process — see {@link Approver#parkAll()}.
   */
  public AgentConfig<T> approver(Approver approver) {
    this.approver = Objects.requireNonNull(approver, "approver must not be null");
    return this;
  }

  /**
   * When the loop stops asking the model for another turn. Default: {@link
   * TerminationPolicy#defaults()}.
   */
  public AgentConfig<T> termination(TerminationPolicy termination) {
    this.termination = Objects.requireNonNull(termination, "termination must not be null");
    return this;
  }

  /**
   * Replaces the default pipeline {@link Memory} floor entirely: the content jurisdiction — told
   * every message-grade happening, asked for the finished {@link
   * org.jwcarman.nessy.api.message.Context} the loop's own {@code ModelCallExecutor} calls the
   * model with. Freedom of retention, rule of law at the border (see {@link Memory}'s own javadoc):
   * summarizing, checkpointing, or embedding memory all implement this one seam.
   */
  public AgentConfig<T> memory(Memory memory) {
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    return this;
  }

  /**
   * Declares the model's total token budget, folded into the built agent's {@code ModelSettings}.
   * Nothing in the loop consumes this yet — it rides {@code ModelSettings} for a future compaction
   * trigger, not read anywhere today.
   */
  public AgentConfig<T> contextWindow(long contextWindow) {
    this.contextWindow = contextWindow;
    return this;
  }

  /**
   * Declares a synchronous listener for this agent, after the harness's own seeded registrations,
   * in the order declared here. Frozen once this config becomes an {@link Agent}: no mutation path
   * exists afterward. A throw from {@code listener} propagates and stops the emitting operation —
   * the veto is the throw.
   */
  @Override
  public <E> AgentConfig<T> listen(Class<E> type, Consumer<E> listener) {
    registrations.add(ListenerRegistration.sync(type, listener));
    return this;
  }

  /**
   * Declares an asynchronous listener for this agent: {@code listener} runs on a fresh virtual
   * thread per event, and whatever it throws reaches {@code onError} instead of the emitting thread
   * — it never vetoes.
   */
  public <E> AgentConfig<T> listenAsync(
      Class<E> type, Consumer<E> listener, Consumer<Throwable> onError) {
    registrations.add(ListenerRegistration.async(type, listener, onError));
    return this;
  }

  /**
   * {@link #listenAsync(Class, Consumer, Consumer)}, reporting a failed listener to an SLF4J {@link
   * Logger} rather than requiring every caller to supply its own handler.
   */
  @Override
  public <E> AgentConfig<T> listenAsync(Class<E> type, Consumer<E> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    return listenAsync(type, listener, t -> LOGGER.error("async event listener failed", t));
  }

  /**
   * Overrides the vocabulary-driven default renderer: {@link InputRenderer#text()} for a {@code
   * String} vocabulary, {@link InputRenderer#json(com.fasterxml.jackson.databind.ObjectMapper)}
   * over the harness mapper otherwise. The sealed-switch renderer over an application's own sealed
   * input vocabulary is the recommended idiom for anything richer than tagged JSON.
   */
  public AgentConfig<T> renderer(InputRenderer<T> renderer) {
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    return this;
  }

  /**
   * The agent-level resolver seam for the who (design of record 2026-08-16-authorization §6):
   * {@code resolver} runs once per evaluated call — impure allowed, since a token exchange or a
   * directory lookup is exactly the kind of I/O the context-assembly stage exists for — and its
   * return value is deposited into {@link
   * org.jwcarman.nessy.api.tool.authorization.AuthzContext#PRINCIPAL}. A {@code null} return is a
   * legitimate "no principal for this conversation" answer, not a failure: the slot stays absent
   * for that call, exactly as if {@code resolver} had never been wired. A thrown exception is a
   * different story — it propagates out of the enricher stage the same as any other enricher
   * failure, so the chokepoint denies that one call closed, naming the enricher stage, rather than
   * ever letting a broken resolver become an allow or escape into the loop. Unwired: {@link
   * org.jwcarman.nessy.api.tool.authorization.AuthzContext#principal()} stays empty for every call,
   * zero ceremony.
   */
  public AgentConfig<T> principal(Function<ConversationId, ?> resolver) {
    this.principalResolver = Objects.requireNonNull(resolver, "resolver must not be null");
    return this;
  }

  /**
   * The whole public surface of {@code spi.intent} (design of record 2026-08-16-authorization §7 —
   * the withdrawn {@code IntentSupport} companion is not coming back): declares this agent's one
   * intent vocabulary, {@code intentType}. The build assembles {@code declare_intent} (whose input
   * type IS {@code intentType}), {@code clear_intent} (no input), and an internal reader enricher
   * from this type plus {@link HarnessConfig#intentStore}'s store — internally, in {@link
   * AgentAssembly}; the caller never learns a second noun.
   *
   * <p>{@code intentType} is an ordinary tool input type: nessy validates no other tool's input
   * type here either, and this method performs no check on {@code intentType} beyond rejecting
   * {@code null} and a repeat call (below). Two things the author owns and this method cannot
   * verify:
   *
   * <ul>
   *   <li>The type must render a JSON schema a model can actually fill — an object with at least
   *       one property, or (for a closed set of variants) a discriminated union. A record or a POJO
   *       with properties is the straightforward choice. A POLYMORPHIC vocabulary — a sealed
   *       interface or abstract base with several shapes — needs the author's own Jackson
   *       polymorphic handling ({@code @JsonTypeInfo} with {@code @JsonSubTypes}, a type indicator
   *       carried in the JSON) and a schema that conveys the alternatives: nessy's own victools
   *       configuration adds no subtype resolution on its own, so a bare sealed interface renders
   *       as an empty, propertyless object schema the model cannot fill.
   *   <li>The declared JSON must bind back into an instance of {@code intentType} through the
   *       harness's {@link com.fasterxml.jackson.databind.ObjectMapper}, and that instance must
   *       round-trip through the {@link org.jwcarman.nessy.spi.intent.IntentStore}. Neither is
   *       checked at wiring time — there is no sample instance to try binding against here. Both
   *       are covered at call time by the same fail-closed machinery every tool call gets: a
   *       binding failure denies that call with a reason the model sees, and a store-write failure
   *       surfaces at declare time.
   * </ul>
   *
   * <p>One field makes the one-intent-per-agent rule true by construction: a second call throws
   * {@link AgentConfigurationException} naming the vocabulary already declared, rather than
   * silently overwriting it.
   *
   * <p>Unwired: no tools are offered, {@link
   * org.jwcarman.nessy.api.tool.authorization.AuthzContext#declaredIntent()} stays empty for every
   * call, and the intent store is never touched.
   *
   * @throws AgentConfigurationException if this agent already declared an intent vocabulary
   */
  public AgentConfig<T> intent(Class<?> intentType) {
    Objects.requireNonNull(intentType, "intentType must not be null");
    if (this.intentType != null) {
      throw new AgentConfigurationException(
          "intent(...) was already called with "
              + this.intentType.getName()
              + " — an agent declares at most one intent vocabulary; the one-field rule makes the"
              + " one-intent-per-agent invariant true by construction, a wiring-time error rather"
              + " than a runtime row overwrite");
    }
    this.intentType = intentType;
    return this;
  }

  /**
   * Declares a subagent over the degenerate {@code String} door (design of record 2026-08-16 §0,
   * ruling 1; §0.5): {@code customizer} fills in a {@link SubagentConfig} — {@link
   * SubagentConfig#name} and {@link SubagentConfig#description} required, everything else trimmed
   * to prompt/model/tools/memory/termination/policy — and this config does the rest at build time:
   * the child is constructed as an ordinary {@code Agent<String>} from this agent's own harness
   * (inheriting its provider, stores, approver, observations, and harness-seeded listeners), a
   * delegation tool naming the child is granted on this agent — its wire shape is v1's {@code
   * Delegation(String task)}, described by {@link SubagentConfig#description}, at {@link
   * SubagentConfig#policy}, default {@link UsagePolicy#allow()} — the completions wiring that wakes
   * this agent once the child settles is arranged internally, and both agents are registered in the
   * harness's own internal name registry — a name already taken anywhere in the harness's whole
   * delegation tree throws {@link IllegalArgumentException} at build time. No renderer is ever
   * required or consulted on this door.
   *
   * <p>{@code customizer} may itself declare {@link SubagentConfig#subagent(SubagentCustomizer)} or
   * {@link SubagentConfig#subagent(Class, SubagentCustomizer)}, nesting a grandchild the same way:
   * the delegation tree is exactly the lexical nesting of these lambdas, so a cycle is
   * unrepresentable — a child is always defined inside its parent and can never refer back to it.
   *
   * @see #subagent(Class, SubagentCustomizer) the typed door
   */
  public AgentConfig<T> subagent(SubagentCustomizer<String> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    SubagentConfig<String> config = new SubagentConfig<>();
    customizer.customize(config);
    subagentAssembly.declareString(config);
    return this;
  }

  /**
   * Declares a subagent over the typed door (design of record 2026-08-16 §0.5): {@code inputType}
   * becomes the delegation tool's own wire shape directly — {@link
   * org.jwcarman.nessy.api.tool.Tool#inputType()} is {@code inputType}, so the model calls this
   * tool with {@code inputType}'s own victools schema, structured arguments instead of a
   * prose-packed task string — and the child is an ordinary {@code Agent<T>} opened through {@link
   * SubagentConfig#renderer}, which is REQUIRED here: build throws {@link IllegalStateException}
   * naming the missing renderer if {@code customizer} never calls it. No silent render-as-JSON
   * default — an explicit renderer makes a deliberate child prompt. Every other piece of {@link
   * #subagent(SubagentCustomizer)}'s own contract (harness inheritance, delegation grant
   * naming/description/policy, internal completions wiring, the internal name registry's duplicate
   * rejection, lexical nesting) applies identically here.
   *
   * @param <X> the subagent's own delegation wire shape — the record its tool arguments deserialize
   *     into
   * @see #subagent(SubagentCustomizer) the degenerate {@code String} door
   */
  public <X> AgentConfig<T> subagent(Class<X> inputType, SubagentCustomizer<X> customizer) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    SubagentConfig<X> config = new SubagentConfig<>();
    customizer.customize(config);
    subagentAssembly.declareTyped(new TypedSubagentDeclaration<>(inputType, config));
    return this;
  }

  /**
   * Turns this config into the {@link Agent} it describes — {@link AgentAssembly}'s own job, never
   * a public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * Harness#agent(AgentCustomizer)} / {@link Harness#agent(Class, AgentCustomizer)}, once {@code
   * customize} has returned, and internally by {@link SubagentAssembly} for each declared subagent.
   */
  Agent<T> build() {
    return AgentAssembly.build(this);
  }

  /**
   * Throws {@link AgentConfigurationException} naming whichever required field is missing — run by
   * {@link AgentAssembly#build(AgentConfig)} before this config is turned into an {@link Agent}.
   */
  void validate() {
    if (name == null) {
      throw new AgentConfigurationException(
          "an agent name is required: call .name(...) before harness.agent(...) returns — the name"
              + " is how parked work finds its way home across restarts, the durable stamp every"
              + " callback door checks a resolution against");
    }
    String resolved = resolvedModel();
    if (resolved == null || resolved.isBlank()) {
      throw new AgentConfigurationException(
          "a model name is required: call model(...) on the agent, or defaultModel(...) on the"
              + " harness");
    }
  }

  /** {@link #model} if set and non-blank, otherwise this config's own harness's default model. */
  String resolvedModel() {
    return (model != null && !model.isBlank()) ? model : harness.defaultModel();
  }

  /**
   * This config's own harness — {@link AgentAssembly}'s and {@link SubagentAssembly}'s window onto
   * the shared infrastructure (provider, session store, parks, observations, mapper, the internal
   * name registry) every agent and subagent it builds inherits.
   */
  Harness harness() {
    return harness;
  }

  String name() {
    return name;
  }

  String systemPrompt() {
    return systemPrompt;
  }

  Integer maxTokens() {
    return maxTokens;
  }

  Set<Capability> capabilities() {
    return capabilities;
  }

  ToolRegistry tools() {
    return tools;
  }

  /**
   * This config's own explicit tool grants, keyed by tool name — {@code null} until {@link
   * #tools(ToolGrant...)} is called.
   */
  Map<String, ToolGrant> explicitGrants() {
    return explicitGrants;
  }

  /**
   * This config's own approver, cascaded down to every subagent {@link SubagentAssembly} builds
   * (design of record 2026-08-16 §3: not a {@link SubagentConfig} knob, inherited by construction).
   */
  Approver approver() {
    return approver;
  }

  TerminationPolicy termination() {
    return termination;
  }

  Long contextWindow() {
    return contextWindow;
  }

  InputRenderer<T> renderer() {
    return renderer;
  }

  /** This config's own principal resolver — {@code null} until {@link #principal(Function)}. */
  Function<ConversationId, ?> principalResolver() {
    return principalResolver;
  }

  /** This config's own declared intent vocabulary — {@code null} until {@link #intent(Class)}. */
  Class<?> intentType() {
    return intentType;
  }

  List<ListenerRegistration> registrations() {
    return registrations;
  }

  /**
   * A defensive snapshot of this config's own explicit tool grants — empty when {@link
   * #tools(ToolGrant...)} was never called — for {@link SubagentAssembly#build()} to merge
   * delegation grants on top of.
   */
  List<ToolGrant> explicitGrantsSnapshot() {
    return explicitGrants == null ? List.of() : List.copyOf(explicitGrants.values());
  }

  /**
   * This config's own subagent-assembly collaborator, reached by a parent's {@link
   * SubagentAssembly} to adopt a child's lexically-nested declarations onto the child's own
   * assembly (design of record 2026-08-16 §0, ruling 1's lexical nesting).
   */
  SubagentAssembly subagentAssembly() {
    return subagentAssembly;
  }

  /**
   * {@link #approver} if set, otherwise {@link Approver#allowAll()} — every tool call is granted
   * with no human in the loop. Design §13.1 requires this fallback to announce itself with a
   * prominent warning — unless no approval path can exist in the first place: {@code grants} empty,
   * or every grant's {@link UsagePolicy} is a canonical {@link UsagePolicy.Static} verdict ({@link
   * UsagePolicy#allow()} or {@link UsagePolicy#deny(String)}), neither of which ever consults the
   * approver. This mirrors the chokepoint's own rung-0 test ({@code GatedToolCallExecutor}) and the
   * report's ({@code AuthorizationReport}) exactly, rather than comparing by identity against
   * {@code allow()} alone — a deny-only agent has no approval path either, and must stay just as
   * silent. A custom policy stays opaque — it might defer to the approver, so its absence still
   * warns, fail-noisy for the unknown.
   */
  Approver resolvedApprover(Map<String, ToolGrant> grants) {
    if (approver != null) {
      return approver;
    }
    boolean noApprovalPathCanExist =
        grants.values().stream().allMatch(grant -> grant.policy() instanceof UsagePolicy.Static);
    if (!noApprovalPathCanExist) {
      LOGGER.warn(
          "no approver configured for this agent: defaulting to Approver.allowAll(), which grants"
              + " every tool call with no human in the loop; call .approver(...) to declare real"
              + " approval authority (design §13.1)");
    }
    return Approver.allowAll();
  }

  /**
   * {@link SubagentAssembly#build()}'s own downgrade warning, voiced through this config's logger
   * (not {@link SubagentAssembly}'s own) — the same logger category every other build-time guard on
   * this class already warns through, so a caller watching {@link AgentConfig}'s own log category
   * sees every one of them, subagent-related or not.
   */
  void warnMissingSubagentLinks() {
    LOGGER.warn(
        "this agent declares a subagent, but the harness's own subagentLinks store was never"
            + " configured even though its session store was — every parent-child delegation"
            + " correlation lives only in memory and is lost on process exit; a child that settles"
            + " after a restart leaves its parent parked forever with nothing logged anywhere; call"
            + " .subagentLinks(...) on the harness (e.g. JdbcPersistence.subagentLinks()) to fix it");
  }

  /**
   * {@link #memory} if set, otherwise the floor: remembers everything verbatim through a
   * transcript, recalls it whole. Warns when the harness's own {@code ConversationStore} was
   * explicitly configured — a conversation persisted there will not carry its transcript across
   * restarts unless this default is overridden, the same set-vs-defaulted mismatch {@link
   * HarnessConfig}'s own parks-defaulting guard checks for. Memory stays agent-scoped even so: this
   * warns rather than auto-wiring a durable transcript from the store.
   */
  Memory resolvedMemory() {
    if (memory != null) {
      return memory;
    }
    if (harness.storeSet()) {
      LOGGER.warn(
          "no memory configured for this agent: defaulting to an in-memory pipeline Memory, even"
              + " though this harness's store was explicitly configured — a conversation persisted"
              + " there will not carry its transcript across restarts; name .memory(...) to match");
    }
    return Memory.pipeline(Transcript.inMemory());
  }
}
