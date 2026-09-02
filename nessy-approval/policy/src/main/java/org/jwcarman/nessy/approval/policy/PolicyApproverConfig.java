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
package org.jwcarman.nessy.approval.policy;

import org.jwcarman.nessy.api.tool.Approver;

/**
 * How one {@link PolicyApprover} is put together.
 *
 * <p>The same shape as {@code HarnessConfig}: a fluent thing handed to a {@code Consumer}, so an
 * application says what it wants in one expression and never holds a half-built object.
 *
 * <pre>{@code
 * Approver gate = PolicyApprover.create(policy -> policy
 *     .engine(opa)
 *     .delegate("humans", desk)
 *     .delegate("security-review", judge));
 * }</pre>
 *
 * <p><b>{@link #delegate} is the allowlist, and adding to it is the privileged act.</b> Every name
 * a policy may use has to be put here by the application, in code, at startup. That is deliberate:
 * given a registry of every approver in the process, a policy file could name one that always says
 * yes, and the gate would be one text edit away from being no gate at all. Registering is a
 * decision a person makes once; naming is what the policy does afterwards.
 */
public interface PolicyApproverConfig {

  /** Who decides. Required. */
  PolicyApproverConfig engine(PolicyEngine engine);

  /**
   * Names an approver the policy is allowed to hand a decision to.
   *
   * @param name what a {@link Verdict.Delegate} says to reach this approver
   * @throws IllegalArgumentException if the name is already taken — a silent overwrite could
   *     replace a strict reviewer with a lenient one and leave no trace
   */
  PolicyApproverConfig delegate(String name, Approver approver);

  /**
   * How deep a chain of delegations may go. Defaults to {@link PolicyApprover#DEFAULT_MAX_DEPTH}.
   *
   * <p>A delegate may itself be a policy approver, so A can delegate to B and B back to A. Nothing
   * in the vocabulary forbids it, so something has to count.
   */
  PolicyApproverConfig maxDepth(int maxDepth);
}
