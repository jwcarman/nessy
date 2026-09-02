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
package org.jwcarman.nessy.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolBindingConfig;

/**
 * What one kind of agent is, while it is being described.
 *
 * <p>Mutable during customization and read once afterwards — a CONFIG, not a builder: fluent
 * setters, and no public way to turn it into anything. Only the factory does that, which is what
 * makes "the factory is the only place a config becomes a harness" true by shape rather than by
 * documentation.
 *
 * @param <O> the observation type
 */
final class EngineHarnessConfig<O> implements HarnessConfig<O> {

  private AgentType type;
  private String systemPrompt = "";
  private ModelId modelId;
  private BacklogCoalescer<O> coalescer = (waiting, arrival) -> append(waiting, arrival);
  private ObservationRenderer<O> renderer;
  private Memory memory;
  private final List<ToolBinding<?>> bindings = new ArrayList<>();

  private static <O> List<org.jwcarman.nessy.api.backlog.BacklogItem<O>> append(
      List<org.jwcarman.nessy.api.backlog.BacklogItem<O>> waiting,
      org.jwcarman.nessy.api.backlog.BacklogItem<O> arrival) {
    List<org.jwcarman.nessy.api.backlog.BacklogItem<O>> all = new ArrayList<>(waiting);
    all.add(arrival);
    return List.copyOf(all);
  }

  @Override
  public HarnessConfig<O> type(AgentType agentType) {
    this.type = Objects.requireNonNull(agentType, "type must not be null");
    return this;
  }

  @Override
  public HarnessConfig<O> coalescer(BacklogCoalescer<O> coalescer) {
    this.coalescer = Objects.requireNonNull(coalescer, "coalescer must not be null");
    return this;
  }

  @Override
  public HarnessConfig<O> systemPrompt(String systemPrompt) {
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    return this;
  }

  @Override
  public HarnessConfig<O> model(ModelId modelId) {
    this.modelId = Objects.requireNonNull(modelId, "modelId must not be null");
    return this;
  }

  @Override
  public <I> HarnessConfig<O> tool(Tool<I> tool) {
    return tool(tool, binding -> {});
  }

  @Override
  public <I> HarnessConfig<O> tool(Tool<I> tool, Consumer<ToolBindingConfig<I>> customizer) {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    BindingConfig<I> binding = new BindingConfig<>();
    customizer.accept(binding);
    bindings.add(new ToolBinding<>(tool, binding.approver, binding.renderer));
    return this;
  }

  @Override
  public HarnessConfig<O> memory(Memory memory) {
    this.memory = java.util.Objects.requireNonNull(memory, "memory must not be null");
    return this;
  }

  @Override
  public HarnessConfig<O> renderer(ObservationRenderer<O> renderer) {
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    return this;
  }

  /** Read once, by the factory, after the customizer has had its say. */
  AgentType agentType() {
    return Objects.requireNonNull(type, "an agent kind must be given a type");
  }

  ModelId modelId() {
    return Objects.requireNonNull(modelId, "an agent kind must be given a model");
  }

  ObservationRenderer<O> observationRenderer() {
    return Objects.requireNonNull(
        renderer,
        "an agent kind must be given a renderer: nothing else knows how to turn one of its"
            + " observations into something a model can read");
  }

  String prompt() {
    return systemPrompt;
  }

  BacklogCoalescer<O> backlogCoalescer() {
    return coalescer;
  }

  /** What the application supplied, or null when it left the choice to us. */
  Memory memory() {
    return memory;
  }

  List<ToolBinding<?>> toolBindings() {
    return List.copyOf(bindings);
  }

  private static final class BindingConfig<I> implements ToolBindingConfig<I> {

    private Approver approver = Approver.always();
    private ActionRenderer<I> renderer = ActionRenderer.byToString();

    @Override
    public ToolBindingConfig<I> approver(Approver approver) {
      this.approver = Objects.requireNonNull(approver, "approver must not be null");
      return this;
    }

    @Override
    public ToolBindingConfig<I> action(ActionRenderer<I> renderer) {
      this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
      return this;
    }
  }
}
