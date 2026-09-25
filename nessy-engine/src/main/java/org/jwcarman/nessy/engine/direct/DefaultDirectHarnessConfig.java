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

package org.jwcarman.nessy.engine.direct;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.ContextConfig;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.InferenceConfig;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ApproverConfig;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.inference.SystemPrompt;

/**
 * What a caller said it wanted, collected before anything is built.
 *
 * <p>The same shape as the queued door's config and deliberately so: a tool bound here behaves the
 * way it behaves there, a context assembled here is assembled the same way, and moving an agent
 * between the two is a change of door rather than a rewrite.
 */
public final class DefaultDirectHarnessConfig<I> implements DirectHarnessConfig<I> {

  DefaultDirectHarnessConfig(AgentType agentType) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
  }

  private final AgentType agentType;
  private SystemPromptSource systemPrompt =
      SystemPromptSource.constant(new SystemPrompt("You are a helpful assistant."));
  private InputRenderer<I> renderer = InputRenderer.asString();
  private final List<NarrationListener> listeners = new ArrayList<>();
  private final List<ToolRequest<?>> tools = new ArrayList<>();
  private final Inference inference = new Inference();

  /** One tool and everything said about it, kept until there is a mapper to bind it with. */
  private record ToolRequest<T>(Tool<T> tool, Customizer<ToolConfig<T>> customizer) {}

  @Override
  public DirectHarnessConfig<I> systemPrompt(String prompt) {
    return systemPrompt(SystemPromptSource.constant(new SystemPrompt(prompt)));
  }

  @Override
  public DirectHarnessConfig<I> systemPrompt(SystemPromptSource source) {
    this.systemPrompt = Objects.requireNonNull(source, "system prompt must not be null");
    return this;
  }

  @Override
  public DirectHarnessConfig<I> inputRenderer(InputRenderer<I> renderer) {
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    return this;
  }

  /** A shortcut into the context, where sources of summaries actually live. */
  @Override
  public DirectHarnessConfig<I> summaries(Summarizer source) {
    return inference(in -> in.context(ctx -> ctx.summaries(source)));
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
  public <T> DirectHarnessConfig<I> tool(Tool<T> tool, Customizer<ToolConfig<T>> customizer) {
    tools.add(new ToolRequest<>(tool, customizer));
    return this;
  }

  @Override
  public AgentType agentType() {
    return agentType;
  }

  SystemPromptSource systemPromptSource() {
    return systemPrompt;
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

  List<ToolBinding<?>> bindings(
      org.jwcarman.nessy.api.tool.InputSchemaGenerator schemas,
      tools.jackson.databind.ObjectMapper mapper) {
    return tools.stream().<ToolBinding<?>>map(request -> bind(request, schemas, mapper)).toList();
  }

  private <T> ToolBinding<T> bind(
      ToolRequest<T> request,
      org.jwcarman.nessy.api.tool.InputSchemaGenerator schemas,
      tools.jackson.databind.ObjectMapper mapper) {
    Binding<T> said = new Binding<>();
    request.customizer().customize(said);
    return new ToolBinding<>(
        request.tool(),
        mapper,
        request.tool().inputSchema(schemas),
        said.timeout,
        said.retryPolicy,
        said.action,
        List.copyOf(said.enrichers),
        said.approver,
        said.approval.timeout,
        said.approval.retryPolicy);
  }

  /** What was said about one tool. */
  private static final class Binding<T> implements ToolConfig<T> {
    private Duration timeout = Duration.ofSeconds(30);
    private RetryPolicy retryPolicy = new RetryPolicy.Never();
    private ActionRenderer<T> action = ActionRenderer.byToString();
    private final List<ApprovalEnricher> enrichers = new ArrayList<>();
    private Approver approver = Approver.allow();
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
    private Duration timeout = Duration.ofMinutes(10);
    private RetryPolicy retryPolicy = new RetryPolicy.Never();

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

  /** The model, the budget, and what it is shown. */
  static final class Inference implements InferenceConfig, ContextConfig {
    private String modelName;
    private int maxTokens = 4096;
    private int maxTail = 50;
    private final List<Summarizer> summaries = new ArrayList<>();
    private final List<AmbientSource> ambient = new ArrayList<>();

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
    public InferenceConfig context(Customizer<ContextConfig> customizer) {
      customizer.customize(this);
      return this;
    }

    @Override
    public InferenceConfig timeout(Duration timeout) {
      // Nothing to time out against: the call is the caller's own thread, and a caller that
      // wants to stop waiting interrupts it.
      return this;
    }

    @Override
    public InferenceConfig retryPolicy(RetryPolicy retryPolicy) {
      return this;
    }

    @Override
    public ContextConfig summaries(Summarizer source) {
      summaries.add(source);
      return this;
    }

    @Override
    public ContextConfig maxTail(int turns) {
      this.maxTail = turns;
      return this;
    }

    @Override
    public ContextConfig ambient(AmbientSource source) {
      ambient.add(source);
      return this;
    }

    String modelName() {
      return modelName;
    }

    int maxTokens() {
      return maxTokens;
    }

    int maxTail() {
      return maxTail;
    }

    List<Summarizer> summaries() {
      return List.copyOf(summaries);
    }

    List<AmbientSource> ambient() {
      return List.copyOf(ambient);
    }
  }
}
