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

import org.jwcarman.nessy.api.conversation.ConversationState;

/**
 * The authority half of a {@link ToolGrant}: whether one call to a granted tool may proceed.
 *
 * <p>{@link #evaluate} is consulted exactly once per call, at the tool call executor's one
 * authority chokepoint, before the tool ever runs and before the approver is ever asked. The model
 * has no say in the outcome — it only ever sees the result, allowed, denied, or approved.
 *
 * <p>{@code evaluate} must be pure: no I/O, no mutation, nothing beyond a function of its two
 * arguments. The executor may call it from any thread and treats an escaping {@code
 * RuntimeException} as a {@link PolicyDecision.Deny} — a broken policy fails closed rather than
 * becoming an allow.
 */
public interface UsagePolicy {

  /** Decides {@code call}'s fate, purely from the call and the session state it arrived in. */
  PolicyDecision evaluate(ToolCall call, ConversationState state);

  /**
   * Every call proceeds; the approver is never consulted. Always the same canonical instance — the
   * identity {@code org.jwcarman.nessy.AgentConfig}'s own approver-defaulting check compares a
   * grant's policy against to tell "no approval path can exist here" from an opaque custom policy
   * that might.
   */
  static UsagePolicy allow() {
    return Allow.INSTANCE;
  }

  /** Every call is refused, with the same reason each time. */
  static UsagePolicy deny(String reason) {
    PolicyDecision decision = new PolicyDecision.Deny(reason);
    return (call, state) -> decision;
  }

  /** Every call defers to the approver — unlike {@link #allow()}, which never asks. */
  static UsagePolicy requireApproval() {
    return (call, state) -> new PolicyDecision.RequireApproval();
  }

  /**
   * The canonical singleton {@link #allow()} returns. A dedicated class rather than a field
   * directly on this interface: interface fields are implicitly {@code public static final}, and a
   * field here would publish this type's shape directly. The type itself is necessarily public —
   * interfaces cannot hide a member type — but its constructor and {@link #INSTANCE} are private,
   * so the canonical instance is reachable only through {@link #allow()}.
   */
  final class Allow implements UsagePolicy {

    private static final UsagePolicy INSTANCE = new Allow();

    private Allow() {}

    @Override
    public PolicyDecision evaluate(ToolCall call, ConversationState state) {
      return new PolicyDecision.Allow();
    }
  }
}
