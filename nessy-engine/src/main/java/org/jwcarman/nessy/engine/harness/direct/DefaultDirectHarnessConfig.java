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

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.ContextConfig;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.InferenceConfig;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.MemorySource;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.StateSource;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ApproverConfig;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.engine.chapter.ChapterSettings;
import org.jwcarman.nessy.engine.inference.Instructions;
import org.jwcarman.nessy.engine.observability.ObservedApprover;
import org.jwcarman.nessy.engine.observability.ObservedTool;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.inference.InferenceOptions;

/**
 * What a caller said it wanted, collected before anything is built.
 *
 * <p>The same shape as the queued door's config and deliberately so: a tool bound here behaves the
 * way it behaves there, a context assembled here is assembled the same way, and moving an agent
 * between the two is a change of door rather than a rewrite.
 */
public final class DefaultDirectHarnessConfig<I> implements DirectHarnessConfig<I> {

  DefaultDirectHarnessConfig(AgentType agentType, ObservationRegistry observations) {
    this(agentType, observations, null, null);
  }

  DefaultDirectHarnessConfig(
      AgentType agentType,
      ObservationRegistry observations,
      @Nullable ProviderId defaultProvider,
      @Nullable InferenceOptions defaultOptions) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    this.inference = new Inference(defaultProvider, defaultOptions);
  }

  private final AgentType agentType;
  private final ObservationRegistry observations;
  private SystemPrompt systemPrompt = new SystemPrompt("You are a helpful assistant.");
  private final Instructions instructions = new Instructions();
  private InputRenderer<I> renderer = InputRenderer.asString();
  private final List<NarrationListener> listeners = new ArrayList<>();
  private final List<ToolRequest<?>> tools = new ArrayList<>();
  private final Inference inference;
  private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;

  /** One tool and everything said about it, kept until there is a mapper to bind it with. */
  private record ToolRequest<T>(Tool<T> tool, Customizer<ToolConfig<T>> customizer) {}

  /** The default bounds a turn without ending one that is merely long; see TurnPolicy. */
  static final TurnPolicy DEFAULT_TURN_POLICY = TurnPolicy.calls(20, 25);

  private TurnPolicy turnPolicy = DEFAULT_TURN_POLICY;

  @Override
  public DirectHarnessConfig<I> turnPolicy(TurnPolicy policy) {
    this.turnPolicy = Objects.requireNonNull(policy, "turn policy must not be null");
    return this;
  }

  public TurnPolicy turnPolicy() {
    return turnPolicy;
  }

  @Override
  public DirectHarnessConfig<I> systemPrompt(String prompt) {
    this.systemPrompt = new SystemPrompt(prompt);
    return this;
  }

  @Override
  public DirectHarnessConfig<I> instructions(String text) {
    instructions.add(text);
    return this;
  }

  @Override
  public DirectHarnessConfig<I> inputRenderer(InputRenderer<I> renderer) {
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    return this;
  }

  /**
   * What was recalled because it bears on the turn being answered, asked afresh on every call.
   *
   * <p>A shortcut into the context, which is where memory sources actually live.
   */
  @Override
  public DirectHarnessConfig<I> memory(MemorySource source) {
    return inference(in -> in.context(ctx -> ctx.memory(source)));
  }

  /**
   * The agent's standing situation, asked afresh on every call with the turn being answered.
   *
   * <p>A shortcut into the context, which is where state sources actually live.
   */
  @Override
  public DirectHarnessConfig<I> state(StateSource source) {
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
  public DirectHarnessConfig<I> ambient(AmbientSource source) {
    return inference(in -> in.context(ctx -> ctx.ambient(source)));
  }

  @Override
  public DirectHarnessConfig<I> chapterPolicy(ChapterPolicy policy) {
    return inference(in -> in.context(ctx -> ctx.chapterPolicy(policy)));
  }

  @Override
  public DirectHarnessConfig<I> summarizer(Summarizer summarizer) {
    return inference(in -> in.context(ctx -> ctx.summarizer(summarizer)));
  }

  @Override
  public DirectHarnessConfig<I> inference(Customizer<InferenceConfig> customizer) {
    customizer.customize(inference);
    return this;
  }

  @Override
  public DirectHarnessConfig<I> listener(NarrationListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    return this;
  }

  @Override
  public DirectHarnessConfig<I> maxInFlight(int maxInFlight) {
    this.maxInFlight = maxInFlight;
    return this;
  }

  @Override
  public <T> DirectHarnessConfig<I> tool(Tool<T> tool, Customizer<ToolConfig<T>> customizer) {
    tools.add(new ToolRequest<>(tool, customizer));
    return this;
  }

  @Override
  public AgentType agentType() {
    return agentType;
  }

  /** The prompt and every section added, as the one text this harness will send. */
  SystemPrompt assembledSystemPrompt() {
    return instructions.after(systemPrompt);
  }

  InputRenderer<I> renderer() {
    return renderer;
  }

  List<NarrationListener> listeners() {
    return List.copyOf(listeners);
  }

  Inference inference() {
    return inference;
  }

  int maxInFlight() {
    return maxInFlight;
  }

  List<ToolBinding<?>> bindings(
      org.jwcarman.nessy.api.JsonSchemaGenerator schemas,
      tools.jackson.databind.ObjectMapper mapper) {
    return tools.stream().<ToolBinding<?>>map(request -> bind(request, schemas, mapper)).toList();
  }

  /**
   * Observed here, whoever registered it -- exactly as the queued door does it. The tool and its
   * approver are wrapped with the engine's own observations, so a tool bound to this door makes the
   * same {@code execute_tool} span a queued one does.
   */
  private <T> ToolBinding<T> bind(
      ToolRequest<T> request,
      org.jwcarman.nessy.api.JsonSchemaGenerator schemas,
      tools.jackson.databind.ObjectMapper mapper) {
    Binding<T> said = new Binding<>();
    request.customizer().customize(said);
    Tool<T> observed = ObservedTool.wrap(request.tool(), observations);
    return new ToolBinding<>(
        observed,
        mapper,
        observed.inputSchema(schemas),
        said.timeout,
        said.retryPolicy,
        said.action,
        List.copyOf(said.enrichers),
        // Only an approver the application chose is worth a span: the default lets every call
        // through, and an "approval" nobody was asked for would mislead a dashboard.
        said.approverChosen ? ObservedApprover.wrap(said.approver, observations) : said.approver,
        said.approval.timeout,
        said.approval.retryPolicy);
  }

  /** What was said about one tool. */
  private static final class Binding<T> implements ToolConfig<T> {
    private Duration timeout = DEFAULT_TOOL_TIMEOUT;
    private RetryPolicy retryPolicy = DEFAULT_RETRY_POLICY;
    private ActionRenderer<T> action = ActionRenderer.byToString();
    private final List<ApprovalEnricher> enrichers = new ArrayList<>();
    private Approver approver = Approver.allow();
    private boolean approverChosen;
    private final Approval approval = new Approval();

    @Override
    public ToolConfig<T> timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    @Override
    public ToolConfig<T> retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    @Override
    public ToolConfig<T> action(ActionRenderer<T> action) {
      this.action = action;
      return this;
    }

    @Override
    public ToolConfig<T> enrich(ApprovalEnricher enricher) {
      enrichers.add(enricher);
      return this;
    }

    @Override
    public ToolConfig<T> approver(Approver approver, Customizer<ApproverConfig> customizer) {
      this.approver = approver;
      this.approverChosen = true;
      customizer.customize(approval);
      return this;
    }
  }

  /**
   * What was said about a tool's approver.
   *
   * <p>Its own class because ToolConfig and ApproverConfig both say timeout and retryPolicy about
   * different things -- how long the work may take, and how long the asking may.
   */
  private static final class Approval implements ApproverConfig {
    private Duration timeout = DEFAULT_APPROVAL_TIMEOUT;
    private RetryPolicy retryPolicy = DEFAULT_RETRY_POLICY;

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

  // What an application gets unless it says otherwise, matching the queued door's defaults --
  // the same setting means the same thing on both doors.
  static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(30);
  static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(10);
  static final RetryPolicy DEFAULT_RETRY_POLICY = new RetryPolicy.Never();
  private static final Duration DEFAULT_INFERENCE_TIMEOUT = Duration.ofMinutes(5);
  // Sixteen times the queued door's EffectsConfig.maxInFlight default, and deliberately not the
  // same number -- see DirectHarnessConfig.maxInFlight for why a harness-wide bound on synchronous
  // callers needs a much higher ceiling than a bound on a poller draining a durable queue.
  static final int DEFAULT_MAX_IN_FLIGHT = 64;

  /** The model, the budget, and what it is shown. */
  static final class Inference implements InferenceConfig, ContextConfig {
    private ProviderId provider;
    private String modelName;
    private int maxTokens = 4096;
    private final Map<String, String> properties = new LinkedHashMap<>();
    private int maxTail = 40;
    private final ChapterSettings chapters = new ChapterSettings();
    private final List<MemorySource> memory = new ArrayList<>();
    private final Set<String> memoryKinds = new LinkedHashSet<>();
    private final List<StateSource> state = new ArrayList<>();
    private final Set<String> stateKinds = new LinkedHashSet<>();
    private final List<AmbientSource> ambient = new ArrayList<>();
    private final Set<String> ambientKinds = new LinkedHashSet<>();
    private Duration timeout = DEFAULT_INFERENCE_TIMEOUT;
    private RetryPolicy retryPolicy = DEFAULT_RETRY_POLICY;

    Inference(@Nullable ProviderId provider, @Nullable InferenceOptions defaults) {
      this.provider = provider;
      if (defaults != null) {
        this.modelName = defaults.modelName();
        if (defaults.hasMaxTokens()) {
          this.maxTokens = defaults.maxTokens();
        }
        this.properties.putAll(defaults.properties());
      }
    }

    @Override
    public InferenceConfig provider(ProviderId id) {
      this.provider = Objects.requireNonNull(id, "id must not be null");
      return this;
    }

    ProviderId provider() {
      return provider;
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
      customizer.customize(this);
      return this;
    }

    @Override
    public InferenceConfig timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Stored, and read back by {@link #retryPolicy()} -- but not honoured. This door does not retry
     * a failed or expired inference; a caller that wants one retried asks again. The value is kept
     * so a reader can see what was configured, not to promise that anything happens because of it.
     */
    @Override
    public InferenceConfig retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
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
    public ContextConfig memory(MemorySource source) {
      Objects.requireNonNull(source, "memory source must not be null");
      // Refused here rather than at render time: two sections under one label leave the model with
      // a
      // contradiction and no way to tell which is current.
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
      // Refused here rather than at render time: two sections under one label leave the model with
      // a
      // contradiction and no way to tell which is current.
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
      // Refused here rather than at render time: two sections under one label leave the model with
      // a
      // contradiction and no way to tell which is current.
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

    String modelName() {
      return modelName;
    }

    int maxTokens() {
      return maxTokens;
    }

    Map<String, String> properties() {
      return Collections.unmodifiableMap(properties);
    }

    private static String requireNonBlank(String text, String argument) {
      Objects.requireNonNull(text, argument + " must not be null");
      if (text.isBlank()) {
        throw new IllegalArgumentException(argument + " must not be blank");
      }
      return text;
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

    Duration timeout() {
      return timeout;
    }

    RetryPolicy retryPolicy() {
      return retryPolicy;
    }
  }
}
