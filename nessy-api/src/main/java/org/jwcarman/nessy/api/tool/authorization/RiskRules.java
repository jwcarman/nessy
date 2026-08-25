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
package org.jwcarman.nessy.api.tool.authorization;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Rule;

/** Rules over {@link ApprovalRequest#RISK} — the one-line judgment every deployment wants. */
public final class RiskRules {

  private RiskRules() {}

  /**
   * Severity below {@code approveAt} approves; from {@code approveAt} up to (but below) {@code
   * denyAt} defers; {@code denyAt} or above denies naming the severity. No risk fact denies closed.
   *
   * @throws IllegalArgumentException if {@code approveAt} is more severe than {@code denyAt}
   */
  public static Rule threshold(RiskLevel approveAt, RiskLevel denyAt) {
    Objects.requireNonNull(approveAt, "approveAt must not be null");
    Objects.requireNonNull(denyAt, "denyAt must not be null");
    if (approveAt.compareTo(denyAt) > 0) {
      throw new IllegalArgumentException("approveAt must not exceed denyAt");
    }
    return Rule.named(
        "risk threshold",
        request -> {
          Optional<RiskAssessment> assessment = request.facts().get(ApprovalRequest.RISK);
          if (assessment.isEmpty()) {
            return new Rule.Verdict.Answered(
                Approval.denied("no risk assessment deposited under 'risk'"));
          }
          RiskLevel severity = assessment.get().risk();
          if (severity.compareTo(approveAt) < 0) {
            return new Rule.Verdict.Answered(Approval.approved());
          }
          if (severity.compareTo(denyAt) < 0) {
            return new Rule.Verdict.Defer();
          }
          return new Rule.Verdict.Answered(
              Approval.denied(
                  "risk severity " + severity + " meets or exceeds threshold " + denyAt));
        });
  }
}
