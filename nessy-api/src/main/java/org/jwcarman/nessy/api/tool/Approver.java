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

import static org.jwcarman.nessy.api.Awaited.ready;
import static org.jwcarman.nessy.api.tool.ApprovalResult.approved;

import org.jwcarman.nessy.api.Awaited;

/**
 * Decides whether one call may run, or says that somebody else will.
 *
 * <p>A facade over anything: a rule, a risk service, an OPA query, a Slack post, a four-eyes
 * workflow, a person at a terminal. None of that is visible to the engine, and all of it is free to
 * be slow -- an approver that needs a human returns {@link Awaited#deferred()} and answers later
 * through {@link Replies}.
 *
 * <p><b>Deferring hands the answer to somebody else.</b> The request carries the agent type, the
 * agent id and the {@link ApprovalRequest#idempotencyKey()} that address the call. An approver may
 * hand them to whatever will answer, or keep nothing, because the approvals waiting on a person can
 * be read from {@link org.jwcarman.nessy.api.AgentWork#waitingApprovals()}. It usually still wants
 * the deadline and whatever it sent -- a message id, a ticket -- so it can tidy up an approval
 * request that expires unanswered: the thing that decided a person was needed is the only thing
 * that knows which person. One case is the exception: when the engine could not record the
 * deferral, the approval request is not listed, and the call expires at its deadline. An approver
 * that kept nothing cannot be answered for that call.
 *
 * <p>Nothing tells an approver that its approval request expired. The deadline it was given is the
 * whole of what it knows, which is enough to sweep its own outstanding approval requests.
 */
public interface Approver {

  /**
   * @param request what is being asked, including the sentence a person is to consent to and the
   *     address a late answer goes to
   * @return a verdict now, or {@link Awaited#deferred()} if one is coming later
   */
  Awaited<ApprovalResult> approve(ApprovalRequest request);

  /**
   * Always yes -- for a tool nobody gates.
   *
   * <p>A real approver rather than an absence to check for, so the engine has one path into a
   * running tool rather than a branch on whether a gate is present. The path that is never
   * exercised is the one a misconfiguration would take.
   */
  static Approver allow() {
    return _ -> ready(approved());
  }
}
