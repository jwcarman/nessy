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

import java.util.Objects;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;

/**
 * The authority half of a {@link ToolGrant}: whether one call to a granted tool may proceed.
 *
 * <p>{@link #evaluate} is consulted exactly once per call, at the tool call executor's one
 * authority chokepoint, before the tool ever runs and before the approver is ever asked. The model
 * has no say in the outcome — it only ever sees the result, allowed, denied, or approved.
 *
 * <p>{@code evaluate} must be pure: no I/O, no mutation, nothing beyond a function of its two
 * arguments — the final {@link AuthzContext} an ordered chain of enrichers assembled, and the
 * tool's own rendered effect. The executor may call it from any thread and treats an escaping
 * {@code RuntimeException} as a {@link PolicyDecision.Deny} naming the policy stage — a broken
 * policy fails closed rather than becoming an allow.
 *
 * <p>{@code E} is the effect type this policy judges. A grant welds it to the tool's own {@code
 * EffectfulTool<I, E>} at compile time (rung 2); every accepting site takes {@code UsagePolicy<?
 * super E>}, so the canonical {@link #allow()}, {@link #deny(String)}, and {@link
 * #requireApproval()} — all {@code UsagePolicy<Object>} — terminate any grant regardless of what it
 * welded.
 *
 * @param <E> the effect type this policy judges
 */
public interface UsagePolicy<E> {

  /** Decides {@code call}'s fate, purely from the final context and the tool's rendered effect. */
  PolicyDecision evaluate(AuthzContext context, E effect);

  /**
   * Every call proceeds; the approver is never consulted. Always the same canonical instance — the
   * identity {@code org.jwcarman.nessy.AgentConfig}'s own approver-defaulting check compares a
   * grant's policy against to tell "no approval path can exist here" from an opaque custom policy
   * that might. Its verdict never depends on context or effect, so the chokepoint fast-paths it
   * (ladder-law rung 0): no effect rendered, no context assembled, no enrichers run.
   */
  static UsagePolicy<Object> allow() {
    return Allow.INSTANCE;
  }

  /**
   * Every call is refused, with the same reason each time. Like {@link #allow()}, its verdict never
   * depends on context or effect, so it shares the same rung-0 fast path.
   */
  static UsagePolicy<Object> deny(String reason) {
    return new Deny(reason);
  }

  /**
   * Every call defers to the approver — unlike {@link #allow()}, which never asks. Context-blind
   * like the other two canonical factories, but NOT rung-0: the approver still needs to see the
   * tool's rendered effect and the assembled context (design §9), so this does not fast-path them
   * away. A context-aware policy may return {@link PolicyDecision.RequireApproval} conditionally
   * instead of using this factory at all — it pays the assembly cost either way.
   */
  static UsagePolicy<Object> requireApproval() {
    return RequireApproval.INSTANCE;
  }

  /**
   * Pins the effect type {@code E} at the call site for a rung-1 lambda reading {@link
   * AuthzContext#call()}/{@link AuthzContext#state()} — {@code UsagePolicy.<Foo>of((context,
   * effect) -> ...)} where target-type inference alone would otherwise leave {@code E} ambiguous.
   */
  static <E> UsagePolicy<E> of(UsagePolicy<E> policy) {
    return Objects.requireNonNull(policy, "policy must not be null");
  }

  /**
   * Marker for a policy whose verdict never depends on context or effect — implemented ONLY by
   * {@code Allow} and {@code Deny}, the two canonical statics (both package-private top-level
   * classes beside this interface, reachable only through {@link #allow()} and {@link
   * #deny(String)}), and sealed shut to exactly those two. The chokepoint checks for this rather
   * than for identity against a single instance, since {@link #deny(String)} cannot be one shared
   * singleton across every reason.
   *
   * <p>This is deliberately closed, not an extension point: the chokepoint's rung-0 fast path skips
   * {@link #evaluate}'s own fail-closed staging entirely (no effect rendered, no context assembled,
   * no enrichers run — nothing there to catch a throw), so a third {@code Static} implementor would
   * bypass fail-closed staging outright, and if its {@link #decision()} ever returned {@link
   * PolicyDecision.RequireApproval} the executor would have no context or effect to hand the
   * approver at all. {@link #requireApproval()} does not implement this marker for exactly that
   * reason.
   */
  sealed interface Static permits Allow, Deny {

    /** The one verdict this policy ever returns — never {@link PolicyDecision.RequireApproval}. */
    PolicyDecision decision();
  }
}
