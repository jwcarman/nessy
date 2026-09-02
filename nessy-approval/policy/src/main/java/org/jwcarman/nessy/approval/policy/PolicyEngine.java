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

import org.jwcarman.nessy.api.tool.ApprovalRequest;

/**
 * Something that decides, right now, what should happen to a call.
 *
 * <p><b>A policy decides now; an approver may take three days.</b> That sentence is the whole
 * reason this interface exists separately from {@code Approver}. Deciding is synchronous and cheap
 * enough to do on any thread. Approving may park a call for a person and survive a restart.
 * Conflating them makes a policy framework either too slow or too weak, and {@link
 * Verdict.Delegate} is the bridge from one world to the other.
 *
 * <p>An implementation may be a remote OPA, a remote Cedar, or a plain Java function — nothing here
 * requires a network. "Externalized" is a deployment choice.
 *
 * <p><b>Failure is not permission.</b> An implementation that cannot reach its engine should say so
 * by throwing; {@link PolicyApprover} turns that into a denial and logs it as a broken gate rather
 * than a decision.
 */
@FunctionalInterface
public interface PolicyEngine {

  Verdict decide(ApprovalRequest request);
}
