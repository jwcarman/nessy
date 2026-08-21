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
package org.jwcarman.nessy.api.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;

/**
 * The authority half of a {@link ToolGrant}: whether one call to a granted tool may proceed.
 *
 * <p>{@link #evaluate} is consulted exactly once per call, at the tool call executor's one
 * authority chokepoint, before the tool ever runs and before the approver is ever asked. The model
 * has no say in the outcome — it only ever sees the result, allowed, denied, or approved.
 *
 * <p>{@code evaluate} must be pure: no I/O, no mutation, nothing beyond a function of its one
 * argument — the final {@link AuthzContext} an ordered chain of enrichers assembled. The executor
 * may call it from any thread and treats an escaping {@code RuntimeException} as a {@link
 * PolicyDecision.Deny} naming the policy stage — a broken policy fails closed rather than becoming
 * an allow.
 *
 * <p>The pipeline is monomorphic (action-wave spec §8): no type parameter here or on {@link
 * org.jwcarman.nessy.api.tool.authorization.Enricher}. The action travels only as {@link
 * AuthzContext#ACTION_KEY}; an action-aware policy recovers it with {@link
 * AuthzContext#action(Class)} and fails closed on its own terms if the slot is empty or mistyped.
 */
public interface UsagePolicy {

  /** Decides {@code call}'s fate, purely from the final context. */
  PolicyDecision evaluate(AuthzContext context);

  /**
   * Every call proceeds; the approver is never consulted. Always the same canonical instance — the
   * identity {@code org.jwcarman.nessy.AgentConfig}'s own approver-defaulting check compares a
   * grant's policy against to tell "no approval path can exist here" from an opaque custom policy
   * that might. Its verdict never depends on context or action, so the chokepoint fast-paths it
   * (ladder-law rung 0): no action rendered, no context assembled, no enrichers run.
   */
  static UsagePolicy allow() {
    return Allow.INSTANCE;
  }

  /**
   * Every call is refused, with the same reason each time. Like {@link #allow()}, its verdict never
   * depends on context or action, so it shares the same rung-0 fast path.
   */
  static UsagePolicy deny(String reason) {
    return new Deny(reason);
  }

  /**
   * Every call defers to the approver — unlike {@link #allow()}, which never asks. Context-blind
   * like the other two canonical factories, but NOT rung-0: the approver still needs to see the
   * grant's rendered action and the assembled context (design §9), so this does not fast-path them
   * away. A context-aware policy may return {@link PolicyDecision.RequireApproval} conditionally
   * instead of using this factory at all — it pays the assembly cost either way.
   */
  static UsagePolicy requireApproval() {
    return RequireApproval.INSTANCE;
  }

  /**
   * Deny-biased conjunction (vocabulary amendment §3): evaluates {@code policies} in order,
   * stopping at the first {@link PolicyDecision.Deny} and surfacing its own reason; if none deny
   * but any returns {@link PolicyDecision.RequireApproval}, that wins; only when every policy
   * allows does the composite allow. Closes the gap that made judging inside an enricher tempting —
   * an org can compose canonical policies instead of writing its own conjunction each time.
   *
   * <p>The composite itself is never {@link Static}: its verdict depends on {@code policies} and,
   * through them, on context and action, so it must take the chokepoint's normal fail-closed
   * staging rather than the rung-0 fast path.
   *
   * @throws IllegalArgumentException if {@code policies} is empty or contains a {@code null}
   *     element
   */
  static UsagePolicy allOf(List<UsagePolicy> policies) {
    Objects.requireNonNull(policies, "policies must not be null");
    if (policies.isEmpty()) {
      throw new IllegalArgumentException("policies must not be empty");
    }
    List<UsagePolicy> ordered = new ArrayList<>(policies.size());
    for (UsagePolicy policy : policies) {
      if (policy == null) {
        throw new IllegalArgumentException("policies must not contain a null element");
      }
      ordered.add(policy);
    }
    return new AllOfPolicy(List.copyOf(ordered));
  }

  /**
   * Pins the target type at the call site for a rung-1 lambda reading {@link AuthzContext#call()}
   * and the context's typed keys — {@code UsagePolicy.of((context) -> ...)} where target-type
   * inference alone would otherwise leave the lambda ambiguous.
   */
  static UsagePolicy of(UsagePolicy policy) {
    return Objects.requireNonNull(policy, "policy must not be null");
  }

  /**
   * Marker for a policy whose verdict never depends on context or action — implemented ONLY by
   * {@code Allow} and {@code Deny}, the two canonical statics (both package-private top-level
   * classes beside this interface, reachable only through {@link #allow()} and {@link
   * #deny(String)}), and sealed shut to exactly those two. The chokepoint checks for this rather
   * than for identity against a single instance, since {@link #deny(String)} cannot be one shared
   * singleton across every reason.
   *
   * <p>This is deliberately closed, not an extension point: the chokepoint's rung-0 fast path skips
   * {@link #evaluate}'s own fail-closed staging entirely (no action rendered, no context assembled,
   * no enrichers run — nothing there to catch a throw), so a third {@code Static} implementor would
   * bypass fail-closed staging outright, and if its {@link #decision()} ever returned {@link
   * PolicyDecision.RequireApproval} the executor would have no context or action to hand the
   * approver at all. {@link #requireApproval()} does not implement this marker for exactly that
   * reason.
   */
  sealed interface Static permits Allow, Deny {

    /** The one verdict this policy ever returns — never {@link PolicyDecision.RequireApproval}. */
    PolicyDecision decision();
  }
}
