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
import java.util.function.Consumer;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * What an application writes to describe a subagent, handed to {@link
 * AgentBuilder#subagent(Consumer)}: a CONFIG, not a builder (design of record 2026-08-16 §0 owner
 * ruling) — fluent setters, no {@code build()}. Only the parent's own builder is ever allowed to
 * turn this into an {@link Agent}; nothing here is half-buildable on its own, because there is
 * nothing here that builds at all.
 *
 * <p>{@link #name} and {@link #description} are required — {@code build()} on the enclosing {@link
 * AgentBuilder} throws {@link IllegalStateException} naming whichever is missing. Everything else
 * this class exposes trims to prompt/model/tools/memory/termination/policy; everything the parent
 * agent's own harness already owns — provider, stores, approver, observations, harness-seeded
 * listeners — is inherited by construction and is not overridable here (design of record 2026-08-16
 * §3).
 *
 * <p>{@link #subagent(Consumer)} lets a child declare its own children, so a delegation tree —
 * A→B→C — is a lexical nesting of these config lambdas, one inside the next: a cycle is
 * unrepresentable, because a child is always defined inside its parent and can never refer back to
 * it (design of record 2026-08-16 §0, ruling 1).
 */
public final class SubagentConfig {

  private String name;
  private String description;
  private String model;
  private String systemPrompt;
  private Integer maxTokens;
  private List<ToolGrant> grants = List.of();
  private Memory memory;
  private TerminationPolicy termination;
  private UsagePolicy policy;
  private final List<SubagentConfig> subagents = new ArrayList<>();

  /** This subagent's required identity — the durable stamp its own parks carry. */
  public SubagentConfig name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Required — becomes the delegation tool's own {@link
   * org.jwcarman.nessy.api.tool.Tool#description()}.
   */
  public SubagentConfig description(String description) {
    this.description = description;
    return this;
  }

  /** Wins over the harness's own default model, exactly like {@link AgentBuilder#model(String)}. */
  public SubagentConfig model(String model) {
    this.model = model;
    return this;
  }

  /** The system prompt sent with every one of this subagent's model calls. */
  public SubagentConfig systemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
    return this;
  }

  /** This subagent's own per-response token ceiling. */
  public SubagentConfig maxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
    return this;
  }

  /** This subagent's own tool grants — the same grant-line-is-the-security-statement contract. */
  public SubagentConfig tools(ToolGrant... grants) {
    Objects.requireNonNull(grants, "grants must not be null");
    for (int i = 0; i < grants.length; i++) {
      Objects.requireNonNull(grants[i], "grants[" + i + "] must not be null");
    }
    this.grants = List.copyOf(Arrays.asList(grants));
    return this;
  }

  /** This subagent's own {@link Memory}, replacing the default pipeline floor. */
  public SubagentConfig memory(Memory memory) {
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    return this;
  }

  /** This subagent's own termination policy. */
  public SubagentConfig termination(TerminationPolicy termination) {
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
  public SubagentConfig policy(UsagePolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    return this;
  }

  /**
   * Nests a child inside this subagent, so the delegation tree can go A→B→C: this subagent's own
   * builder grants a delegation tool to whatever {@code config} describes, exactly the way {@link
   * AgentBuilder#subagent(Consumer)} does for the top-level agent.
   */
  public SubagentConfig subagent(Consumer<SubagentConfig> config) {
    Objects.requireNonNull(config, "config must not be null");
    SubagentConfig nested = new SubagentConfig();
    config.accept(nested);
    subagents.add(nested);
    return this;
  }

  /**
   * Throws {@link IllegalStateException} naming whichever required field is missing — run by the
   * enclosing {@link AgentBuilder#build()} before this config is turned into an {@link Agent}.
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

  List<SubagentConfig> subagents() {
    return subagents;
  }
}
