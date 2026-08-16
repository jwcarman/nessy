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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * What an application writes to describe a subagent, handed to {@link
 * AgentBuilder#subagent(SubagentCustomizer)} / {@link AgentBuilder#subagent(Class,
 * SubagentCustomizer)}: a CONFIG, not a builder (design of record 2026-08-16 §0 owner ruling) —
 * fluent setters, no {@code build()}. Only the parent's own builder is ever allowed to turn this
 * into an {@link Agent}; nothing here is half-buildable on its own, because there is nothing here
 * that builds at all.
 *
 * <p>{@link #name} and {@link #description} are required — {@code build()} on the enclosing {@link
 * AgentBuilder} throws {@link IllegalStateException} naming whichever is missing. Everything else
 * this class exposes trims to prompt/model/tools/memory/termination/policy/renderer; everything the
 * parent agent's own harness already owns — provider, stores, approver, observations,
 * harness-seeded listeners — is inherited by construction and is not overridable here (design of
 * record 2026-08-16 §3).
 *
 * <p>{@code T} is the subagent's own delegation wire shape (design of record 2026-08-16 §0.5): the
 * degenerate {@code String} door wraps it in the v1 {@code Delegation(String task)} tool schema and
 * never consults {@link #renderer}; the typed door makes {@code T} the delegation tool's own input
 * type directly — its victools schema IS the tool schema — and {@link #renderer} is required to
 * reach the child {@code Agent<T>} with it.
 *
 * <p>{@link #subagent(SubagentCustomizer)} and {@link #subagent(Class, SubagentCustomizer)} let a
 * child declare its own children, so a delegation tree — A→B→C — is a lexical nesting of these
 * config lambdas, one inside the next: a cycle is unrepresentable, because a child is always
 * defined inside its parent and can never refer back to it (design of record 2026-08-16 §0, ruling
 * 1).
 *
 * @param <T> this subagent's own delegation wire shape
 */
public final class SubagentConfig<T> {

  private String name;
  private String description;
  private String model;
  private String systemPrompt;
  private Integer maxTokens;
  private List<ToolGrant> grants = List.of();
  private Memory memory;
  private TerminationPolicy termination;
  private UsagePolicy policy;
  private InputRenderer<T> renderer;
  private final List<SubagentConfig<String>> stringSubagents = new ArrayList<>();
  private final List<TypedSubagentDeclaration<?>> typedSubagents = new ArrayList<>();

  /** This subagent's required identity — the durable stamp its own parks carry. */
  public SubagentConfig<T> name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Required — becomes the delegation tool's own {@link
   * org.jwcarman.nessy.api.tool.Tool#description()}.
   */
  public SubagentConfig<T> description(String description) {
    this.description = description;
    return this;
  }

  /** Wins over the harness's own default model, exactly like {@link AgentBuilder#model(String)}. */
  public SubagentConfig<T> model(String model) {
    this.model = model;
    return this;
  }

  /** The system prompt sent with every one of this subagent's model calls. */
  public SubagentConfig<T> systemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
    return this;
  }

  /** This subagent's own per-response token ceiling. */
  public SubagentConfig<T> maxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
    return this;
  }

  /** This subagent's own tool grants — the same grant-line-is-the-security-statement contract. */
  public SubagentConfig<T> tools(ToolGrant... grants) {
    Objects.requireNonNull(grants, "grants must not be null");
    for (int i = 0; i < grants.length; i++) {
      Objects.requireNonNull(grants[i], "grants[" + i + "] must not be null");
    }
    this.grants = List.copyOf(Arrays.asList(grants));
    return this;
  }

  /** This subagent's own {@link Memory}, replacing the default pipeline floor. */
  public SubagentConfig<T> memory(Memory memory) {
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    return this;
  }

  /** This subagent's own termination policy. */
  public SubagentConfig<T> termination(TerminationPolicy termination) {
    this.termination = Objects.requireNonNull(termination, "termination must not be null");
    return this;
  }

  /**
   * The DELEGATION tool's own policy — whether the parent's model may call this subagent, not any
   * policy the subagent's own tools carry (those are declared through {@link
   * #tools(ToolGrant...)}). Default: {@link UsagePolicy#allow()}. A parking child under {@link
   * UsagePolicy#requireApproval()} is fully supported (design of record 2026-08-16 §4): the
   * delegation call parks for approval, then the child's own execution parks again once approved —
   * two waits, not a wedge.
   */
  public SubagentConfig<T> policy(UsagePolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    return this;
  }

  /**
   * REQUIRED on the typed door — {@link AgentBuilder#subagent(Class, SubagentCustomizer)} fails
   * loudly, naming this field, if it is never called; forbidden-to-matter on the degenerate {@code
   * String} door (design of record 2026-08-16 §0.5), which never reads it even if set, since the
   * wire shape there is always the v1 {@code Delegation(String task)} wrapper, not {@code T}
   * itself.
   */
  public SubagentConfig<T> renderer(InputRenderer<T> renderer) {
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    return this;
  }

  /**
   * Nests a degenerate {@code String}-vocabulary child inside this subagent, so the delegation tree
   * can go A→B→C: this subagent's own builder grants a delegation tool to whatever {@code
   * customizer} describes, exactly the way {@link AgentBuilder#subagent(SubagentCustomizer)} does
   * for the top-level agent.
   */
  public SubagentConfig<T> subagent(SubagentCustomizer<String> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    SubagentConfig<String> nested = new SubagentConfig<>();
    customizer.customize(nested);
    stringSubagents.add(nested);
    return this;
  }

  /**
   * Nests a typed child inside this subagent (design of record 2026-08-16 §0.5): {@code inputType}
   * becomes the nested delegation tool's own wire shape, exactly the way {@link
   * AgentBuilder#subagent(Class, SubagentCustomizer)} does for the top-level agent.
   */
  public <X> SubagentConfig<T> subagent(Class<X> inputType, SubagentCustomizer<X> customizer) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    SubagentConfig<X> nested = new SubagentConfig<>();
    customizer.customize(nested);
    typedSubagents.add(new TypedSubagentDeclaration<>(inputType, nested));
    return this;
  }

  /**
   * Throws {@link IllegalStateException} naming whichever required field is missing — run by the
   * enclosing {@link AgentBuilder#build()} before this config is turned into an {@link Agent}. The
   * typed door's own required-renderer check is a separate, later step (it is not a property of the
   * config alone, but of which door built it).
   */
  void validate() {
    if (name == null || name.isBlank()) {
      throw new IllegalStateException(
          "a subagent config requires .name(...): it is the durable stamp this subagent's own"
              + " parks carry, and the delegation tool's own wire name");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalStateException(
          "a subagent config requires .description(...): it IS the delegation tool's own"
              + " description, what the parent's model reads to decide whether to delegate");
    }
  }

  String name() {
    return name;
  }

  String description() {
    return description;
  }

  String model() {
    return model;
  }

  String systemPrompt() {
    return systemPrompt;
  }

  Integer maxTokens() {
    return maxTokens;
  }

  List<ToolGrant> grants() {
    return grants;
  }

  Memory memory() {
    return memory;
  }

  TerminationPolicy termination() {
    return termination;
  }

  UsagePolicy policy() {
    return policy;
  }

  InputRenderer<T> renderer() {
    return renderer;
  }

  List<SubagentConfig<String>> stringSubagents() {
    return stringSubagents;
  }

  List<TypedSubagentDeclaration<?>> typedSubagents() {
    return typedSubagents;
  }
}
