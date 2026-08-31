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

import org.jwcarman.nessy.api.Awaited;

public interface Approver {
  /**
   * Decides, or says a person will.
   *
   * <p>An approver is the thing that knows a human is needed and where that question belongs — a
   * queue, a page, a pager. {@code replyTo} is how it says where the answer comes back, handed down
   * because only the engine can mint an address it will honour.
   *
   * <p>Beside the request rather than inside it, deliberately: {@link ApprovalRequest} describes
   * the question and is exactly what an approvals page stores and renders, while {@code replyTo} is
   * the authority to settle the call. Keeping them apart is what stops a credential ending up in a
   * projection.
   *
   * @param request what is being asked
   * @param context what else this decision offers — today, where a person's answer goes
   */
  Awaited<ApprovalResult> approve(ApprovalRequest request, ApprovalContext context);

  /**
   * The approver that always says yes — for a tool nobody gates.
   *
   * <p>Exists so every {@link ToolBinding} carries an approver and the engine has one path rather
   * than a branch on whether a gate is present.
   */
  static Approver always() {
    return (request, context) -> Awaited.ready(ApprovalResult.approved());
  }
}
