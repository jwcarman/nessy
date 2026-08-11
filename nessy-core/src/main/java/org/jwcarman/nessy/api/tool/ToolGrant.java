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
 * call, and the {@link UsagePolicy} the tool call executor consults before it runs.
 *
 * <p>This is the security statement of the harness, and there is exactly one way to write it:
 * {@link #grant(Tool, UsagePolicy)}. No bare grant, no derived floor, no re-dressing an existing
 * grant with a different policy — a grant does not exist until its authority is answered. The
 * executor consults only the policy a grant carries.
 */
public record ToolGrant(Tool<?> tool, UsagePolicy policy) {

  public ToolGrant {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
  }

  /** The sole construction path: a tool and the policy that governs it, stated together. */
  public static ToolGrant grant(Tool<?> tool, UsagePolicy policy) {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    return new ToolGrant(tool, policy);
  }
}
