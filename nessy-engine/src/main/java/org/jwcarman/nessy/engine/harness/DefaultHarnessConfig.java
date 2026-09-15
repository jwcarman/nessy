package org.jwcarman.nessy.engine.harness;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.ContextConfig;
import org.jwcarman.nessy.api.EffectsConfig;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.InferenceConfig;
import org.jwcarman.nessy.api.ObservationCoalescer;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.SummarySource;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ApproverConfig;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * The engine's {@link org.jwcarman.nessy.api.HarnessConfig}.
 *
 * <p>Mutable while a customizer runs, read once afterwards. Defaults are seeded from the factory,
 * so anything an application configured once is already here before the customizer is called.
 *
 * @param <O> the observation type
 */
public final class DefaultHarnessConfig<O> implements HarnessConfig<O> {

  private static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(30);
  // Not at all, for a tool and for an inference alike. A harness that knows its provider flakes
  // or its tool is idempotent says so; the engine does not guess on its behalf.
  private static final RetryPolicy DEFAULT_TOOL_RETRY_POLICY = new RetryPolicy.Never();
  private static final RetryPolicy DEFAULT_INFERENCE_RETRY_POLICY = new RetryPolicy.Never();

  /**
   * Generous, because the deferred path is the ordinary one for an approval: a human takes as long
   * as a human takes, and a question that expires while somebody is reading it is worse than one
   * that stands a little too long.
   */
  private static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(10);

  private final TypeRef<O> observationType;
  private final ObjectMapper mapper;
  private final InputSchemaGenerator schemas;

  private AgentType agentType;
  private SystemPromptSource systemPrompt;
  private ObservationRenderer<O> renderer = ObservationRenderer.asString();
  private ObservationCoalescer<O> coalescer = ObservationCoalescer.keepAll();

  private final Inference inference;
  private final Effects effects = new Effects();
  private final List<ToolBinding<?>> tools = new ArrayList<>();
  private final List<AgentEventListener> listeners = new ArrayList<>();

  DefaultHarnessConfig(
      TypeRef<O> observationType,
      Defaults defaults,
      ObjectMapper mapper,
      InputSchemaGenerator schemas) {
    this.observationType = observationType;
    this.inference = new Inference(defaults);
    this.mapper = mapper;
    this.schemas = schemas;
  }

  /** What the factory already knows, so an agent type only states its differences. */
  record Defaults(InferenceProvider provider, InferenceOptions options) {}

  @Override
  public DefaultHarnessConfig<O> listener(AgentEventListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    return this;
  }

  List<AgentEventListener> listeners() {
    return List.copyOf(listeners);
  }

  @Override
  public DefaultHarnessConfig<O> agentType(AgentType agentType) {
    this.agentType = agentType;
    return this;
  }

  @Override
  public DefaultHarnessConfig<O> systemPrompt(String prompt) {
    return systemPrompt(SystemPromptSource.constant(new SystemPrompt(prompt)));
  }

  @Override
  public DefaultHarnessConfig<O> systemPrompt(SystemPromptSource source) {
    this.systemPrompt = source;
    return this;
  }

  @Override
  public DefaultHarnessConfig<O> observationRenderer(ObservationRenderer<O> renderer) {
    this.renderer = renderer;
    return this;
  }

  @Override
  public DefaultHarnessConfig<O> observationCoalescer(ObservationCoalescer<O> coalescer) {
    this.coalescer = coalescer;
    return this;
  }

  @Override
  public DefaultHarnessConfig<O> inference(Consumer<InferenceConfig> customizer) {
    customizer.accept(inference);
    return this;
  }

  @Override
  public DefaultHarnessConfig<O> effects(Consumer<EffectsConfig> customizer) {
    customizer.accept(effects);
    return this;
  }

  /**
   * Binds one tool to this harness.
   *
   * <p>Everything derived is derived now, once: the schema is generated, the argument codec is
   * created, and the application's terms are read off a {@code ToolConfig} that exists only for the
   * length of the customizer. A tool's shape cannot change between calls, and doing this per call
   * would put a reflective walk of the input type on the path of every inference.
   */
  @Override
  public <I> DefaultHarnessConfig<O> tool(Tool<I> tool, Consumer<ToolConfig<I>> customizer) {
    ToolTerms<I> terms = new ToolTerms<>(DEFAULT_TOOL_TIMEOUT, DEFAULT_TOOL_RETRY_POLICY);
    customizer.accept(terms);
    tools.add(
        new ToolBinding<>(
            tool,
            mapper,
            tool.inputSchema(schemas),
            terms.timeout,
            terms.retryPolicy,
            terms.action,
            terms.enrichers,
            terms.approver,
            terms.approvalTimeout,
            terms.approvalRetryPolicy));
    return this;
  }

  // ---- what the factory reads back -------------------------------------------------------

  TypeRef<O> observationType() {
    return observationType;
  }

  ObservationRenderer<O> renderer() {
    return renderer;
  }

  ObservationCoalescer<O> coalescer() {
    return coalescer;
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
    private ActionRenderer<I> action = ActionRenderer.byToString();
    private final List<ApprovalEnricher> enrichers = new ArrayList<>();
    private Approver approver = Approver.allow();
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
    public ToolConfig<I> action(ActionRenderer<I> action) {
      this.action = action;
      return this;
    }

    @Override
    public ToolConfig<I> enrich(ApprovalEnricher enricher) {
      enrichers.add(Objects.requireNonNull(enricher, "enricher must not be null"));
      return this;
    }

    @Override
    public ToolConfig<I> approver(Approver approver, Consumer<ApproverConfig> customizer) {
      ApprovalTerms terms = new ApprovalTerms();
      customizer.accept(terms);
      this.approver = approver;
      this.approvalTimeout = terms.timeout;
      this.approvalRetryPolicy = terms.retryPolicy;
      return this;
    }
  }

  /**
   * One approver's terms, which are genuinely its own.
   *
   * <p>Asking is its own effect with its own row, so this retry policy governs the question not
   * arriving and nothing else. It can be widened where a tool's cannot: re-asking changes nothing
   * in the world, while re-running a tool whose outcome was never observed may repeat something
   * that already happened.
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
    return Objects.requireNonNull(agentType, "agentType must be set");
  }

  /**
   * Required, and deliberately so. An agent without one works perfectly and does the wrong job: a
   * generic assistant wearing this agent type's name, with nothing in the logs to say so.
   */
  SystemPromptSource requiredSystemPrompt() {
    return Objects.requireNonNull(systemPrompt, "systemPrompt must be set");
  }

  static final class Inference implements InferenceConfig {

    private InferenceProvider provider;
    private String modelName;
    private int maxTokens;
    private final Context context = new Context();
    private Duration timeout = Duration.ofMinutes(5);
    private RetryPolicy retryPolicy = DEFAULT_INFERENCE_RETRY_POLICY;

    private Inference(Defaults defaults) {
      this.provider = defaults.provider();
      this.modelName = defaults.options().modelName();
      this.maxTokens = defaults.options().maxTokens();
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
    public InferenceConfig context(java.util.function.Consumer<ContextConfig> customizer) {
      customizer.accept(context);
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

    InferenceProvider provider() {
      return provider;
    }

    InferenceOptions options() {
      return new InferenceOptions(modelName, maxTokens);
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

    /** Summaries, the tail, and background: everything that goes in that is not the call itself. */
    static final class Context implements ContextConfig {

      private final List<SummarySource> summaries = new ArrayList<>();
      private final List<AmbientSource> ambient = new ArrayList<>();
      private final Set<String> ambientKinds = new LinkedHashSet<>();
      private int maxTail = 20;

      @Override
      public ContextConfig summaries(SummarySource source) {
        summaries.add(Objects.requireNonNull(source, "summary source must not be null"));
        return this;
      }

      @Override
      public ContextConfig maxTail(int turns) {
        if (turns <= 0) {
          throw new IllegalArgumentException("maxTail must be positive");
        }
        this.maxTail = turns;
        return this;
      }

      @Override
      public ContextConfig ambient(AmbientSource source) {
        ambient.add(Objects.requireNonNull(source, "ambient source must not be null"));
        return this;
      }

      @Override
      public ContextConfig ambient(Ambient constant) {
        Objects.requireNonNull(constant, "ambient must not be null");
        // Only a constant's kind is known at configuration time; a source's is known per call.
        if (!ambientKinds.add(constant.kind())) {
          throw new IllegalArgumentException(
              "two ambient sources offer the kind '" + constant.kind() + "'");
        }
        return ambient(AmbientSource.constant(constant));
      }

      List<SummarySource> summaries() {
        return List.copyOf(summaries);
      }

      int maxTail() {
        return maxTail;
      }

      List<AmbientSource> ambient() {
        return List.copyOf(ambient);
      }
    }
  }

  static final class Effects implements EffectsConfig {

    private Duration pollInterval = Duration.ofMillis(250);
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
