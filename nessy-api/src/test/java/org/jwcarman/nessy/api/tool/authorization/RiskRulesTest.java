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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Rule;

class RiskRulesTest {

  private static ApprovalRequest requestAt(RiskLevel level) {
    // Choose a Likelihood/Impact pair whose RiskAssessment.of(...) yields `level`; the existing
    // RiskAssessmentTest's severity matrix lists them. Deposit under ApprovalRequest.RISK.
    ApprovalRequest.Draft draft =
        ApprovalRequest.draft(
            "ops",
            "a1",
            new ToolCall("c1", "x", JsonNodeFactory.instance.objectNode()),
            Map.of(),
            new ObjectMapper());
    draft.deposit(ApprovalRequest.RISK, RiskAssessments.at(level)); // test helper: see below
    return draft.freeze();
  }

  @Test
  void belowApproveAtApproves() {
    Rule rule = RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH);

    assertThat(rule.judge(requestAt(RiskLevel.LOW)))
        .isEqualTo(new Rule.Verdict.Answered(new Approval.Approved(Optional.empty())));
  }

  @Test
  void betweenTheThresholdsDefers() {
    Rule rule = RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH);

    assertThat(rule.judge(requestAt(RiskLevel.HIGH))).isEqualTo(new Rule.Verdict.Defer());
  }

  @Test
  void atDenyAtDeniesNamingTheSeverity() {
    Rule rule = RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH);

    Rule.Verdict verdict = rule.judge(requestAt(RiskLevel.VERY_HIGH));

    assertThat(verdict).isInstanceOf(Rule.Verdict.Answered.class);
    Approval answer = ((Rule.Verdict.Answered) verdict).approval();
    assertThat(((Approval.Denied) answer).reason()).contains("VERY_HIGH");
  }

  @Test
  void noRiskFactDeniesClosed() {
    Rule rule = RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH);
    ApprovalRequest bare =
        ApprovalRequest.draft(
                "ops",
                "a1",
                new ToolCall("c1", "x", JsonNodeFactory.instance.objectNode()),
                Map.of(),
                new ObjectMapper())
            .freeze();

    Rule.Verdict verdict = rule.judge(bare);

    Approval answer = ((Rule.Verdict.Answered) verdict).approval();
    assertThat(((Approval.Denied) answer).reason()).contains("no risk");
  }

  @Test
  void approveAtAboveDenyAtIsRefused() {
    assertThatThrownBy(() -> RiskRules.threshold(RiskLevel.VERY_HIGH, RiskLevel.LOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
