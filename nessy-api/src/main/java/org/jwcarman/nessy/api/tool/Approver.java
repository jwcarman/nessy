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
  Awaited<ApprovalResult> approve(ApprovalRequest request);

  /**
   * The approver that always says yes — for a tool nobody gates.
   *
   * <p>Exists so every {@link ToolBinding} carries an approver and the engine has one path rather
   * than a branch on whether a gate is present.
   */
  static Approver always() {
    return request -> Awaited.ready(ApprovalResult.approved());
  }
}
