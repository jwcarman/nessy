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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
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
    if (config.intentType() != null) {
      // The whole "second noun" the withdrawn IntentSupport would have been: one field on
      // AgentConfig folds declare_intent + clear_intent into this same tools()/explicitGrants()
      // pair SubagentAssembly.build() just extended above, the identical merge idiom.
      List<ToolGrant> combined = new ArrayList<>(config.explicitGrantsSnapshot());
      combined.addAll(
          IntentAssembly.grants(harness.intentStore(), config.intentType(), harness.mapper()));
      config.tools(combined.toArray(new ToolGrant[0]));
    }
    ToolRegistry resolvedTools = Optional.ofNullable(config.tools()).orElseGet(ToolRegistry::of);
    Map<String, ToolGrant> resolvedGrants =
        crossCutEnrichers(
            Optional.ofNullable(config.explicitGrants()).orElseGet(Map::of),
            crossCuttingEnrichers(harness, config));
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

  /**
   * The two effect-blind enrichers {@code config} wired at the agent level — {@link
   * AgentConfig#principal(Function)} and {@link AgentConfig#intent(Class)} — in that order,
   * principal before intent. Empty when neither was called: the common case, costing nothing.
   */
  private static List<Enricher<Object>> crossCuttingEnrichers(
      Harness harness, AgentConfig<?> config) {
    List<Enricher<Object>> enrichers = new ArrayList<>();
    Function<ConversationId, ?> resolver = config.principalResolver();
    if (resolver != null) {
      enrichers.add(
          (context, effect) -> {
            Object principal = resolver.apply(context.conversationId());
            return principal == null
                ? context
                : context.with(AuthorizationContext.PRINCIPAL, principal);
          });
    }
    if (config.intentType() != null) {
      enrichers.add(
          IntentAssembly.reader(harness.intentStore(), config.intentType(), harness.mapper()));
    }
    return enrichers;
  }

  /**
   * Prepends {@code crossCutting} onto every non-static grant's own enrichers, leaving a {@link
   * UsagePolicy.Static} grant (rung 0) untouched — the ladder law's own fast path never even
   * inspects a grant's enrichers for a static policy, but skipping the allocation here says so
   * explicitly: rung-0 grants assemble no context, and this ensures nothing here disturbs that
   * (design of record 2026-08-16-authorization §1, Task 2's own ladder law).
   */
  private static Map<String, ToolGrant> crossCutEnrichers(
      Map<String, ToolGrant> grants, List<Enricher<Object>> crossCutting) {
    if (crossCutting.isEmpty()) {
      return grants;
    }
    Map<String, ToolGrant> wrapped = new LinkedHashMap<>();
    for (Map.Entry<String, ToolGrant> entry : grants.entrySet()) {
      ToolGrant grant = entry.getValue();
      if (grant.policy() instanceof UsagePolicy.Static) {
        wrapped.put(entry.getKey(), grant);
        continue;
      }
      List<Enricher<?>> extended = new ArrayList<>(crossCutting);
      extended.addAll(grant.enrichers());
      wrapped.put(entry.getKey(), new ToolGrant(grant.tool(), grant.policy(), extended));
    }
    return wrapped;
  }
}
