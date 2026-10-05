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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.ContextConfig;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.EffectsConfig;
import org.jwcarman.nessy.api.InferenceConfig;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.MemorySource;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarnessConfig;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.StateSource;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ApproverConfig;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.chapter.ChapterSettings;
import org.jwcarman.nessy.engine.inference.Instructions;
import org.jwcarman.nessy.engine.observability.ObservedApprover;
import org.jwcarman.nessy.engine.observability.ObservedTool;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.InferenceOptions;
import tools.jackson.databind.ObjectMapper;

/**
 * The engine's {@link org.jwcarman.nessy.api.QueuedHarnessConfig}.
 *
 * <p>Mutable while a customizer runs, read once afterwards. Defaults are seeded from the factory,
 * so anything an application configured once is already here before the customizer is called.
 *
 * @param <I> the input type
 */
public final class DefaultQueuedHarnessConfig<I> implements QueuedHarnessConfig<I> {

  private static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(30);
  // Not at all, for a tool and for an inference alike. A harness that knows its provider flakes
  // or its tool is idempotent says so; the engine does not guess on its behalf.
  private static final RetryPolicy DEFAULT_TOOL_RETRY_POLICY = new RetryPolicy.Never();
  private static final RetryPolicy DEFAULT_INFERENCE_RETRY_POLICY = new RetryPolicy.Never();

  /**
   * Generous, because the deferred path is the ordinary one for an approval: a human takes as long
   * as a human takes, and an approval request that expires while somebody is reading it is worse
   * than one that stands a little too long.
   */
  private static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(10);

  private final TypeRef<I> inputType;
  private final ObjectMapper mapper;
  private final JsonSchemaGenerator schemas;

  private final AgentType agentType;
  private SystemPrompt systemPrompt;
  private final Instructions instructions = new Instructions();
  private InputRenderer<I> renderer = InputRenderer.asString();
  private Optional<Stringifier<I>> label = Optional.empty();
  private BacklogPolicy<I> policy = BacklogPolicy.keepAll();

  private final Inference inference;
  private final Effects effects = new Effects();
  private final List<ToolBinding<?>> tools = new ArrayList<>();
  private final ObservationRegistry observations;
  private final List<NarrationListener> listeners = new ArrayList<>();

  DefaultQueuedHarnessConfig(
      AgentType agentType,
      TypeRef<I> inputType,
      Defaults defaults,
      ObjectMapper mapper,
      JsonSchemaGenerator schemas,
      ObservationRegistry observations) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.inputType = inputType;
    this.inference = new Inference(defaults);
    this.mapper = mapper;
    this.schemas = schemas;
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  /** What the factory already knows, so an agent type only states its differences. */
  record Defaults(@Nullable ProviderId provider, @Nullable InferenceOptions options) {}

  @Override
  public DefaultQueuedHarnessConfig<I> listener(NarrationListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    return this;
  }

  List<NarrationListener> listeners() {
    return List.copyOf(listeners);
  }

  @Override
  public AgentType agentType() {
    return agentType;
  }

  /** The default bounds a turn without ending one that is merely long; see TurnPolicy. */
  static final TurnPolicy DEFAULT_TURN_POLICY = TurnPolicy.calls(20, 25);

  private TurnPolicy turnPolicy = DEFAULT_TURN_POLICY;

  @Override
  public DefaultQueuedHarnessConfig<I> turnPolicy(TurnPolicy policy) {
    this.turnPolicy = Objects.requireNonNull(policy, "turn policy must not be null");
    return this;
  }

  public TurnPolicy turnPolicy() {
    return turnPolicy;
  }

  @Override
  public DefaultQueuedHarnessConfig<I> systemPrompt(String prompt) {
    this.systemPrompt = new SystemPrompt(prompt);
    return this;
  }

  @Override
  public DefaultQueuedHarnessConfig<I> instructions(String text) {
    instructions.add(text);
    return this;
  }

  @Override
  public DefaultQueuedHarnessConfig<I> inputRenderer(InputRenderer<I> renderer) {
    this.renderer = renderer;
    return this;
  }

  @Override
  public DefaultQueuedHarnessConfig<I> inputLabel(Stringifier<I> label) {
    this.label = Optional.of(Objects.requireNonNull(label, "label must not be null"));
    return this;
  }

  @Override
  public DefaultQueuedHarnessConfig<I> backlogPolicy(BacklogPolicy<I> policy) {
    this.policy = policy;
    return this;
  }

  /**
   * What was recalled because it bears on the turn being answered, asked afresh on every call.
   *
   * <p>A shortcut into the context, which is where memory sources actually live.
   */
  @Override
  public DefaultQueuedHarnessConfig<I> memory(MemorySource source) {
    return inference(in -> in.context(ctx -> ctx.memory(source)));
  }

  /**
   * The agent's standing situation, asked afresh on every call with the turn being answered.
   *
   * <p>A shortcut into the context, which is where state sources actually live.
   */
  @Override
  public DefaultQueuedHarnessConfig<I> state(StateSource source) {
    return inference(in -> in.context(ctx -> ctx.state(source)));
  }

  /**
   * Something the model sees every turn.
   *
   * <p>A shortcut into the context, which is where ambient sources actually live: this exists so
   * something equipping an agent can add one without knowing the shape of the inference
   * configuration.
   */
  @Override
  public DefaultQueuedHarnessConfig<I> ambient(AmbientSource source) {
    return inference(in -> in.context(ctx -> ctx.ambient(source)));
  }

  @Override
  public DefaultQueuedHarnessConfig<I> chapterPolicy(ChapterPolicy policy) {
    return inference(in -> in.context(ctx -> ctx.chapterPolicy(policy)));
  }

  @Override
  public DefaultQueuedHarnessConfig<I> summarizer(Summarizer summarizer) {
    return inference(in -> in.context(ctx -> ctx.summarizer(summarizer)));
  }

  @Override
  public DefaultQueuedHarnessConfig<I> inference(Customizer<InferenceConfig> customizer) {
    customizer.customize(inference);
    return this;
  }

  @Override
  public DefaultQueuedHarnessConfig<I> effects(Customizer<EffectsConfig> customizer) {
    customizer.customize(effects);
    return this;
  }

  /**
   * Binds one tool to this harness.
   *
   * <p>Everything derived is derived now, once: the schema is generated, the argument codec is
   * created, and the application's terms are read off a {@code ToolConfig} that exists only for the
   * length of the customizer. A tool's shape cannot change between calls, and doing this per call
   * would put a reflective walk of the input type on the path of every inference.
   *
   * <p><b>Observed here, whoever registered it.</b> The tool and its approver are wrapped with the
   * engine's own observations, so a tool bound by hand and one bound by the Boot starter make the
   * same {@code execute_tool} span. An application that is not tracing pays for a check per call.
   */
  @Override
  public <T> DefaultQueuedHarnessConfig<I> tool(
      Tool<T> tool, Customizer<ToolConfig<T>> customizer) {
    ToolTerms<T> terms = new ToolTerms<>(DEFAULT_TOOL_TIMEOUT, DEFAULT_TOOL_RETRY_POLICY);
    customizer.customize(terms);
    Tool<T> observed = ObservedTool.wrap(tool, observations);
    tools.add(
        new ToolBinding<>(
            observed,
            mapper,
            observed.inputSchema(schemas),
            terms.timeout,
            terms.retryPolicy,
            terms.action,
            terms.result,
            terms.enrichers,
            // Only an approver the application chose is worth a span: the default lets every
            // call through, and an "approval" nobody was asked for would mislead a dashboard.
            terms.approverChosen
                ? ObservedApprover.wrap(terms.approver, observations)
                : terms.approver,
            terms.approvalTimeout,
            terms.approvalRetryPolicy));
    return this;
  }

  // ---- what the factory reads back -------------------------------------------------------

  TypeRef<I> inputType() {
    return inputType;
  }

  InputRenderer<I> renderer() {
    return renderer;
  }

  Optional<Stringifier<I>> label() {
    return label;
  }

  BacklogPolicy<I> policy() {
    return policy;
  }

  Inference inference() {
    return inference;
  }

  Effects effects() {
    return effects;
  }

  /** The tools this harness offers, in the order they were bound. */
  Tools tools() {
    return new Tools(tools);
  }

  /**
   * How long a call is worth waiting for, until {@code ToolConfig} can say otherwise per tool.
   *
   * <p>Shorter than an inference's, because a tool is usually something local or an API call rather
   * than a model generating tokens, and a turn is held open for the whole of it.
   */
  Duration toolTimeout() {
    return DEFAULT_TOOL_TIMEOUT;
  }

  /** What asking about a call is worth, for a call whose tool is no longer bound. */
  Duration approvalTimeout() {
    return DEFAULT_APPROVAL_TIMEOUT;
  }

  /**
   * One tool's terms, as the application states them.
   *
   * <p>Lives only while the customizer runs; what survives is the {@link ToolBinding} built from
   * it. Mutable for the same reason the harness config is: a fluent customizer is the shape an
   * application reads best, and nothing shares one.
   */
  private static final class ToolTerms<I> implements ToolConfig<I> {

    private Duration timeout;
    private RetryPolicy retryPolicy;
    private Optional<Stringifier<I>> action = Optional.empty();
    private Optional<Stringifier<ToolResult.Success>> result = Optional.empty();
    private final List<ApprovalEnricher> enrichers = new ArrayList<>();
    private Approver approver = Approver.allow();
    private boolean approverChosen;
    private Duration approvalTimeout = DEFAULT_APPROVAL_TIMEOUT;
    private RetryPolicy approvalRetryPolicy = DEFAULT_TOOL_RETRY_POLICY;

    private ToolTerms(Duration timeout, RetryPolicy retryPolicy) {
      this.timeout = timeout;
      this.retryPolicy = retryPolicy;
    }

    @Override
    public ToolConfig<I> timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    @Override
    public ToolConfig<I> retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    @Override
    public ToolConfig<I> action(Stringifier<I> action) {
      this.action = Optional.of(action);
      return this;
    }

    @Override
    public ToolConfig<I> result(Stringifier<ToolResult.Success> result) {
      this.result = Optional.of(result);
      return this;
    }

    @Override
    public ToolConfig<I> enrich(ApprovalEnricher enricher) {
      enrichers.add(Objects.requireNonNull(enricher, "enricher must not be null"));
      return this;
    }

    @Override
    public ToolConfig<I> approver(Approver approver, Customizer<ApproverConfig> customizer) {
      ApprovalTerms terms = new ApprovalTerms();
      customizer.customize(terms);
      this.approver = approver;
      this.approverChosen = true;
      this.approvalTimeout = terms.timeout;
      this.approvalRetryPolicy = terms.retryPolicy;
      return this;
    }
  }

  /**
   * One approver's terms, which are genuinely its own.
   *
   * <p>Asking is its own effect with its own row, so this retry policy governs the approval request
   * not arriving and nothing else. It can be widened where a tool's cannot: re-asking changes
   * nothing in the world, while re-running a tool whose outcome was never observed may repeat
   * something that already happened.
   */
  private static final class ApprovalTerms implements ApproverConfig {

    private Duration timeout = DEFAULT_APPROVAL_TIMEOUT;
    private RetryPolicy retryPolicy = DEFAULT_TOOL_RETRY_POLICY;

    @Override
    public ApproverConfig timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    @Override
    public ApproverConfig retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }
  }

  /**
   * Nothing, by default, and that is the safe direction rather than the lazy one.
   *
   * <p>An inference that never reached a provider changed nothing by being repeated. A tool call
   * whose outcome was never observed may well have run -- charged a card, sent a message -- so
   * repeating it is a decision about the world. An application that knows its tool is idempotent is
   * the only one in a position to say so.
   */
  RetryPolicy toolRetryPolicy() {
    return DEFAULT_TOOL_RETRY_POLICY;
  }

  AgentType requiredAgentType() {
    return agentType;
  }

  /**
   * Required, and deliberately so. An agent without one works perfectly and does the wrong job: a
   * generic assistant wearing this agent type's name, with nothing in the logs to say so.
   */
  SystemPrompt requiredSystemPrompt() {
    return instructions.after(Objects.requireNonNull(systemPrompt, "systemPrompt must be set"));
  }

  static final class Inference implements InferenceConfig {

    private ProviderId provider;
    private String modelName;
    // Matches the direct door's default (see DefaultDirectHarnessConfig.Inference); a factory
    // default without a max tokens still gets a real cap rather than an unbounded answer.
    private int maxTokens = 4096;
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Context context = new Context();
    private Duration timeout = Duration.ofMinutes(5);
    private RetryPolicy retryPolicy = DEFAULT_INFERENCE_RETRY_POLICY;

    private Inference(Defaults defaults) {
      this.provider = defaults.provider();
      if (defaults.options() != null) {
        this.modelName = defaults.options().modelName();
        if (defaults.options().hasMaxTokens()) {
          this.maxTokens = defaults.options().maxTokens();
        }
        this.properties.putAll(defaults.options().properties());
      }
    }

    @Override
    public InferenceConfig provider(ProviderId id) {
      this.provider = Objects.requireNonNull(id, "id must not be null");
      return this;
    }

    @Override
    public InferenceConfig model(String modelName) {
      this.modelName = modelName;
      return this;
    }

    @Override
    public InferenceConfig maxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    @Override
    public InferenceConfig property(String name, String value) {
      properties.put(requireNonBlank(name, "name"), requireNonBlank(value, "value"));
      return this;
    }

    @Override
    public InferenceConfig context(Customizer<ContextConfig> customizer) {
      customizer.customize(context);
      return this;
    }

    @Override
    public InferenceConfig timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    @Override
    public InferenceConfig retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    ProviderId provider() {
      return provider;
    }

    String modelName() {
      return modelName;
    }

    InferenceOptions options() {
      return new InferenceOptions(modelName, maxTokens, properties);
    }

    private static String requireNonBlank(String text, String argument) {
      Objects.requireNonNull(text, argument + " must not be null");
      if (text.isBlank()) {
        throw new IllegalArgumentException(argument + " must not be blank");
      }
      return text;
    }

    Context context() {
      return context;
    }

    Duration timeout() {
      return timeout;
    }

    RetryPolicy retryPolicy() {
      return retryPolicy;
    }

    /** The tail and background: everything that goes in that is not the call itself. */
    static final class Context implements ContextConfig {

      private final List<MemorySource> memory = new ArrayList<>();
      private final Set<String> memoryKinds = new LinkedHashSet<>();
      private final List<StateSource> state = new ArrayList<>();
      private final Set<String> stateKinds = new LinkedHashSet<>();
      private final List<AmbientSource> ambient = new ArrayList<>();
      private final Set<String> ambientKinds = new LinkedHashSet<>();
      private int maxTail = 40;
      private final ChapterSettings chapters = new ChapterSettings();

      @Override
      public ContextConfig maxTail(int turns) {
        if (turns <= 0) {
          throw new IllegalArgumentException("maxTail must be positive");
        }
        this.maxTail = turns;
        return this;
      }

      @Override
      public ContextConfig memory(MemorySource source) {
        Objects.requireNonNull(source, "memory source must not be null");
        // Refused here rather than at render time: two sections under one label leave the model
        // with a contradiction and no way to tell which is current.
        if (!memoryKinds.add(source.kind())) {
          throw new IllegalArgumentException(
              "two memory sources offer the kind '" + source.kind() + "'");
        }
        memory.add(source);
        return this;
      }

      @Override
      public ContextConfig state(StateSource source) {
        Objects.requireNonNull(source, "state source must not be null");
        // Refused here rather than at render time: two sections under one label leave the model
        // with a contradiction and no way to tell which is current.
        if (!stateKinds.add(source.kind())) {
          throw new IllegalArgumentException(
              "two state sources offer the kind '" + source.kind() + "'");
        }
        state.add(source);
        return this;
      }

      @Override
      public ContextConfig ambient(AmbientSource source) {
        Objects.requireNonNull(source, "ambient source must not be null");
        // Refused here rather than at render time: two sections under one label leave the model
        // with a contradiction and no way to tell which is current.
        if (!ambientKinds.add(source.kind())) {
          throw new IllegalArgumentException(
              "two ambient sources offer the kind '" + source.kind() + "'");
        }
        ambient.add(source);
        return this;
      }

      @Override
      public ContextConfig chapterPolicy(ChapterPolicy policy) {
        this.chapters.policy(policy);
        return this;
      }

      @Override
      public ContextConfig summarizer(Summarizer summarizer) {
        this.chapters.summarizer(summarizer);
        return this;
      }

      @Override
      public ContextConfig maxChapterLength(int turns) {
        this.chapters.maxLength(turns);
        return this;
      }

      @Override
      public ContextConfig chapterLeaseTtl(Duration ttl) {
        this.chapters.leaseTtl(ttl);
        return this;
      }

      @Override
      public ContextConfig withoutChapters() {
        this.chapters.off();
        return this;
      }

      ChapterSettings chapters() {
        return chapters;
      }

      @Override
      public ContextConfig ambient(Ambient constant) {
        Objects.requireNonNull(constant, "ambient must not be null");
        return ambient(AmbientSource.constant(constant));
      }

      int maxTail() {
        return maxTail;
      }

      List<MemorySource> memory() {
        return List.copyOf(memory);
      }

      List<StateSource> state() {
        return List.copyOf(state);
      }

      List<AmbientSource> ambient() {
        return List.copyOf(ambient);
      }
    }
  }

  static final class Effects implements EffectsConfig {

    // The backstop, not the path: work this process writes is nudged into a pass at once, so the
    // poll only bounds how late it finds retries coming due, timeouts, and rows another process
    // wrote.
    private Duration pollInterval = Duration.ofSeconds(1);
    private int maxInFlight = 4;

    @Override
    public EffectsConfig pollInterval(Duration pollInterval) {
      this.pollInterval = pollInterval;
      return this;
    }

    @Override
    public EffectsConfig maxInFlight(int maxInFlight) {
      this.maxInFlight = maxInFlight;
      return this;
    }

    Duration pollInterval() {
      return pollInterval;
    }

    int maxInFlight() {
      return maxInFlight;
    }
  }
}
