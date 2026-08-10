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

/**
 * A capability and the authority to use it, declared together: which {@link Tool} an agent may
 * call, and the {@link UsagePolicy} the engine consults before it runs.
 *
 * <p>This is the security statement of the harness. Granting a tool without saying anything more
 * falls back to {@link #grant(Tool)}'s derived default — the same floor {@link
 * Tool#requiresApproval()} always drew — but the policy can be loosened or tightened at
 * construction time, per agent, per grant. Nowhere else: the engine consults only the policy a
 * grant carries and never reads {@link Tool#requiresApproval()} itself.
 */
public record ToolGrant(Tool<?> tool, UsagePolicy policy) {

  public ToolGrant {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
  }

  /**
   * The default grant: {@link Tool#requiresApproval()} becomes {@link
   * UsagePolicy#requireApproval()}, and everything else becomes {@link UsagePolicy#allow()}. This
   * is what {@code tools(Tool...)} uses to auto-wrap, so an agent that never mentions grants
   * behaves exactly as it did before grants existed.
   */
  public static ToolGrant grant(Tool<?> tool) {
    Objects.requireNonNull(tool, "tool must not be null");
    return new ToolGrant(
        tool, tool.requiresApproval() ? UsagePolicy.requireApproval() : UsagePolicy.allow());
  }

  /** The same tool, a different policy. */
  public ToolGrant with(UsagePolicy policy) {
    return new ToolGrant(tool, policy);
  }
}
