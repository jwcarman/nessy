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
package org.jwcarman.nessy.api.tool.risk;

import org.jwcarman.nessy.api.tool.ApprovalRequest;

/**
 * What one call is worth worrying about.
 *
 * <p>Takes the whole {@link ApprovalRequest} rather than the tool's typed input, and that is a
 * deliberate trade. An {@code Approver} is shared across tools with different inputs — one desk
 * serves them all — so a typed assessor could only reach an approver through an unchecked cast, and
 * this codebase does not suppress warnings. What an assessment usually turns on is the tool name,
 * the described action, and facts an earlier approver deposited, all of which are on the request;
 * an assessor that needs the arguments can read {@code request.call().input()} and knows its own
 * type.
 *
 * <p><b>It is not asked whether to allow the call.</b> It says how bad the call would be if it went
 * wrong and how likely that is; {@link Risk} turns that into an answer. Keeping the two apart is
 * what lets one assessor serve a strict environment and a lax one.
 */
@FunctionalInterface
public interface RiskAssessor {

  RiskAssessment assess(ApprovalRequest request);

  /** The same verdict for every call — right for a tool whose risk does not vary with its input. */
  static RiskAssessor always(RiskAssessment assessment) {
    return request -> assessment;
  }
}
