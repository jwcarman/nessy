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
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.internal.subagent.AgentTools;

/**
 * The subagent-assembly machinery split out of {@link AgentBuilder} (final review's Monster Class
 * finding, java:S6539): every {@code .subagent(...)} declaration {@code owner} collects, and the
 * up-front-validate-then-build-then-register machinery {@link AgentBuilder#build()} runs over them
 * at build time (design of record 2026-08-16 §0/§3). One instance is owned per {@link
 * AgentBuilder}, package-private, reaching back into {@code owner}'s own public builder surface
 * (name/model/tools/listen/approver/...) to configure each child — nothing here is a public API of
 * its own.
 */
final class SubagentAssembly {

  private final AgentBuilder<?> owner;
  private final List<SubagentConfig<String>> stringConfigs = new ArrayList<>();
  private final List<TypedSubagentDeclaration<?>> typedConfigs = new ArrayList<>();

  SubagentAssembly(AgentBuilder<?> owner) {
    this.owner = owner;
  }

  /** Declares one string-door child, in {@link AgentBuilder#subagent(SubagentCustomizer)}. */
  void declareString(SubagentConfig<String> config) {
    stringConfigs.add(config);
  }

  /** Declares one typed-door child, in {@link AgentBuilder#subagent(Class, SubagentCustomizer)}. */
  void declareTyped(TypedSubagentDeclaration<?> declaration) {
    typedConfigs.add(declaration);
  }

  /**
   * Adopts {@code config}'s own nested declarations (both doors) onto this assembly — how a child's
   * lexically-nested {@code .subagent(...)} calls join its own builder's assembly in {@link
   * #configureChild}.
   */
  private void adoptNested(SubagentConfig<?> config) {
    stringConfigs.addAll(config.stringSubagents());
    typedConfigs.addAll(config.typedSubagents());
  }

  /**
   * Builds every declared subagent — string-door declarations first, then typed-door declarations,
   * in each door's own declaration order: each becomes a real child {@link Agent}, granted to
   * {@code owner} as a delegation tool ({@link AgentBuilder#tools(ToolGrant...)} is called once
   * more here, merging any delegation grants after whatever {@code owner}'s own {@link
   * AgentBuilder#tools(ToolGrant...)} already declared — last write wins on a name collision, the
   * same rule {@link AgentBuilder#tools(ToolGrant...)} already has), and returns {@code owner}'s
   * own direct children, keyed by name, for {@link Agent.Coordination}. Empty when no subagent was
   * ever declared — {@code owner}'s tools are left untouched in that case, so a plain agent's grant
   * set is unaffected.
   *
   * <p>Every declared config, at every depth, is validated <em>before</em> any of them is built or
   * registered: a later sibling's missing name/description/renderer must never leave an earlier
   * sibling already sitting in the harness's internal registry when the throw reaches the caller —
   * the fix would otherwise be "rename it" instead of a clean rebuild.
   */
  Map<String, Agent<?>> build() {
    if (stringConfigs.isEmpty() && typedConfigs.isEmpty()) {
      return Map.of();
    }
    Harness harness = owner.harness();
    if (harness.storeSet() && !harness.subagentLinksSet()) {
      owner.warnMissingSubagentLinks();
    }
    for (SubagentConfig<String> config : stringConfigs) {
      validateTree(config);
    }
    for (TypedSubagentDeclaration<?> declaration : typedConfigs) {
      validateTypedDeclaration(declaration);
    }
    Map<String, Agent<?>> childrenByName = new LinkedHashMap<>();
    List<ToolGrant> delegationGrants = new ArrayList<>();
    try {
      for (SubagentConfig<String> config : stringConfigs) {
        Agent<String> child = buildStringChild(harness, config);
        childrenByName.put(config.name(), child);
        delegationGrants.add(
            ToolGrant.grant(
                AgentTools.subagent(child, config.description(), harness.subagentLinks()),
                Optional.ofNullable(config.policy()).orElseGet(UsagePolicy::allow)));
      }
      for (TypedSubagentDeclaration<?> declaration : typedConfigs) {
        delegationGrants.add(buildTypedGrant(harness, declaration, childrenByName));
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
    List<ToolGrant> combined = new ArrayList<>(owner.explicitGrantsSnapshot());
    combined.addAll(delegationGrants);
    owner.tools(combined.toArray(new ToolGrant[0]));
    return childrenByName;
  }

  /**
   * Recursively validates {@code config} and everything nested inside it (both doors), naming
   * whichever required field is missing — the up-front pass {@link #build()} runs before building
   * or registering anything, so a deep sibling's bad config never leaves an earlier one
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
   * SubagentConfig#renderer} must be set, or {@link AgentBuilder#build()} fails loudly naming the
   * missing renderer and the declaration's own {@code inputType} — no silent render-as-JSON default
   * (design of record 2026-08-16 §0.5).
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
   * {@code harness} (the same construction path {@link Harness#agent()} uses — the "String door",
   * which neither requires nor consults a renderer, unlike the typed door): inherits the harness's
   * provider, stores, approver, observations, and harness-seeded listeners; owns its own name,
   * prompt, model, tool grants, memory, and termination policy (design of record 2026-08-16 §3).
   * {@code config}'s own nested declarations recurse through this same method / {@link
   * #buildTypedChild}, so a grandchild is built and registered before this child is.
   *
   * <p>The completions listener that wakes the built agent's own parent once a further descendant
   * settles is registered here, synchronously, before {@link AgentBuilder#build()} runs — the same
   * sync semantics {@code AgentTools.completions}'s own javadoc requires. {@code config} was
   * already validated by {@link #build()}'s own up-front pass.
   */
  private Agent<String> buildStringChild(Harness harness, SubagentConfig<String> config) {
    AgentBuilder<String> childBuilder =
        new AgentBuilder<>(harness, String.class, InputRenderer.text());
    configureChild(childBuilder, config);
    return childBuilder.build();
  }

  /**
   * One typed-door subagent (design of record 2026-08-16 §0.5), built as an {@code Agent<T>}
   * through {@code config}'s own required {@link SubagentConfig#renderer} — already verified
   * present by {@link #build()}'s up-front {@link #validateTypedDeclaration} pass, so this method
   * never itself throws for a missing renderer. Otherwise identical to {@link #buildStringChild}:
   * same harness inheritance, same recursive nesting, same synchronous completions wiring.
   */
  private <T> Agent<T> buildTypedChild(Harness harness, TypedSubagentDeclaration<T> declaration) {
    SubagentConfig<T> config = declaration.config();
    AgentBuilder<T> childBuilder =
        new AgentBuilder<>(harness, declaration.inputType(), config.renderer());
    configureChild(childBuilder, config);
    return childBuilder.build();
  }

  /** {@link #buildTypedChild}'s own grant, paired with the child it just built. */
  private <T> ToolGrant buildTypedGrant(
      Harness harness,
      TypedSubagentDeclaration<T> declaration,
      Map<String, Agent<?>> childrenByName) {
    SubagentConfig<T> config = declaration.config();
    Agent<T> child = buildTypedChild(harness, declaration);
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
    if (owner.approver() != null) {
      // Not a SubagentConfig knob (design of record 2026-08-16 §3: the approver is inherited by
      // construction, never owned by a subagent) — copied forward from whatever owner's own
      // approver is, cascading down through every further nesting level the same way.
      childBuilder.approver(owner.approver());
    }
    if (!config.grants().isEmpty()) {
      childBuilder.tools(config.grants().toArray(new ToolGrant[0]));
    }
    childBuilder.subagentAssembly().adoptNested(config);
    Harness harness = owner.harness();
    childBuilder.listen(
        ConversationSettled.class,
        AgentTools.completions(harness.subagentLinks(), harness.parks(), harness.subagents()));
  }
}
