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

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.internal.ConversationLoop;
import org.jwcarman.nessy.spi.execute.EffectExecutors;
import org.jwcarman.nessy.spi.execute.GatedToolCallExecutor;
import org.jwcarman.nessy.spi.execute.ProviderModelCallExecutor;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * The runtime-assembly machinery split out of {@link AgentConfig} (java:S6539 — the same Monster
 * Class discipline that already produced {@link SubagentAssembly}): folds a validated {@link
 * AgentConfig}'s resolved fields together with its own harness's collaborators (provider, store,
 * parks, observations, mapper) into the finished {@link Agent}. Stateless — one static entry point,
 * reached only from {@link AgentConfig#build()} — so this class owns none of {@link AgentConfig}'s
 * own required-field validation or warn-on-default logging (those stay on {@link AgentConfig}
 * itself, so a caller watching that class's own logger category sees every one of them); it only
 * ever receives already-resolved or already-warned-about values.
 */
final class AgentAssembly {

  private static final int DEFAULT_MAX_TOKENS = 4096;

  /** {@code ""} — no system prompt. */
  private static final String DEFAULT_SYSTEM_PROMPT = "";

  private AgentAssembly() {}

  /**
   * Validates {@code config} (name, model), then assembles the {@link Agent} it describes: the
   * model-call and tool-call executors, the conversation loop, and the agent itself — registered
   * into {@code config}'s own harness's internal name registry before it is returned.
   */
  static <T> Agent<T> build(AgentConfig<T> config) {
    config.validate();
    Harness harness = config.harness();
    ModelSettings settings =
        new ModelSettings(
            config.resolvedModel(),
            Optional.ofNullable(config.systemPrompt()).orElse(DEFAULT_SYSTEM_PROMPT),
            Optional.ofNullable(config.maxTokens()).orElse(DEFAULT_MAX_TOKENS),
            Optional.ofNullable(config.capabilities()).orElseGet(Set::of),
            config.contextWindow());
    ListenerRegistry events = harness.registry().extendedWith(config.registrations());
    Map<String, Agent<?>> childrenByName = config.subagentAssembly().build();
    ToolRegistry resolvedTools = Optional.ofNullable(config.tools()).orElseGet(ToolRegistry::of);
    Map<String, ToolGrant> resolvedGrants =
        Optional.ofNullable(config.explicitGrants()).orElseGet(Map::of);
    // Constructed once here and handed to both the model-call executor and the Agent: the
    // invariant is one Memory instance per agent, so the wire request and contextFor never
    // disagree about what a call sees.
    Memory resolvedMemory = config.resolvedMemory();
    EffectExecutors executors =
        new EffectExecutors(
            new ProviderModelCallExecutor(
                harness.provider(),
                settings,
                resolvedTools,
                resolvedMemory,
                harness.observations()),
            new GatedToolCallExecutor(
                config.name(),
                resolvedTools,
                resolvedGrants,
                config.resolvedApprover(resolvedGrants),
                harness.mapper(),
                events,
                harness.observations()));
    ConversationLoop loop =
        new ConversationLoop(
            new ConversationLoop.Collaborators(
                executors,
                resolvedMemory,
                Optional.ofNullable(config.termination()).orElseGet(TerminationPolicy::defaults),
                harness.store(),
                harness.parks(),
                events),
            harness.observations(),
            config.name());
    Agent<T> agent =
        new Agent<>(
            config.name(),
            loop,
            events,
            harness.store(),
            new Agent.Coordination(harness.parks(), childrenByName),
            resolvedMemory,
            config.renderer());
    harness.subagents().register(agent);
    return agent;
  }
}
