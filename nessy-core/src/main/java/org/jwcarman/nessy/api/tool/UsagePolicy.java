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
 * <p>{@link #evaluate} is consulted exactly once per call, at the engine's one authority
 * chokepoint, before the tool ever runs and before the approver is ever asked. The model has no say
 * in the outcome — it only ever sees the result, allowed, denied, or approved.
 *
 * <p>{@code evaluate} must be pure: no I/O, no mutation, nothing beyond a function of its two
 * arguments. The engine may call it from any thread and treats an escaping {@code RuntimeException}
 * as a {@link PolicyDecision.Deny} — a broken policy fails closed rather than becoming an allow.
 */
public interface UsagePolicy {

  /** Decides {@code call}'s fate, purely from the call and the session state it arrived in. */
  PolicyDecision evaluate(ToolCall call, ConversationState state);

  /** Every call proceeds; the approver is never consulted. */
  static UsagePolicy allow() {
    return (call, state) -> new PolicyDecision.Allow();
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
}
