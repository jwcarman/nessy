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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.UsagePolicy;

class RiskPoliciesTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "spend", JsonNodeFactory.instance.objectNode());

  private static AuthzContext freshContext() {
    return AuthzContext.of("test-agent", CALL);
  }

  private static AuthzContext contextWithSeverity(RiskLevel severity) {
    RiskAssessment assessment = new RiskAssessment(severity, severity, List.of());
    return freshContext().with(AuthzContext.RISK_KEY, assessment);
  }

  @Nested
  class Construction_guard {

    @Test
    void rejectsAnApproveAtThatExceedsDenyAt() {
      assertThatThrownBy(() -> RiskPolicies.threshold(RiskLevel.HIGH, RiskLevel.MODERATE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("approveAt");
    }
  }

  @Nested
  class Threshold_semantics {

    @Test
    void aSeverityBelowApproveAtIsAllowed() {
      UsagePolicy<Object> policy = RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH);
      AuthzContext context = contextWithSeverity(RiskLevel.VERY_LOW);

      assertThat(policy.evaluate(context, CALL)).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void aSeverityAtApproveAtRequiresApproval() {
      UsagePolicy<Object> policy = RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH);
      AuthzContext context = contextWithSeverity(RiskLevel.LOW);

      assertThat(policy.evaluate(context, CALL)).isEqualTo(new PolicyDecision.RequireApproval());
    }

    @Test
    void aSeverityJustBelowDenyAtRequiresApproval() {
      UsagePolicy<Object> policy = RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH);
      AuthzContext context = contextWithSeverity(RiskLevel.MODERATE);

      assertThat(policy.evaluate(context, CALL)).isEqualTo(new PolicyDecision.RequireApproval());
    }

    @Test
    void aSeverityAtDenyAtIsDeniedNamingTheSeverityAndThreshold() {
      UsagePolicy<Object> policy = RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH);
      AuthzContext context = contextWithSeverity(RiskLevel.HIGH);

      PolicyDecision decision = policy.evaluate(context, CALL);

      assertThat(decision).isInstanceOf(PolicyDecision.Deny.class);
      String reason = ((PolicyDecision.Deny) decision).reason();
      assertThat(reason).contains("HIGH");
    }

    @Test
    void aSeverityAboveDenyAtIsDenied() {
      UsagePolicy<Object> policy = RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH);
      AuthzContext context = contextWithSeverity(RiskLevel.VERY_HIGH);

      assertThat(policy.evaluate(context, CALL)).isInstanceOf(PolicyDecision.Deny.class);
    }

    @Test
    void anAbsentRiskAssessmentFailsClosedWithADenyNamingTheMissingSlot() {
      UsagePolicy<Object> policy = RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.HIGH);
      AuthzContext context = freshContext();

      PolicyDecision decision = policy.evaluate(context, CALL);

      assertThat(decision).isInstanceOf(PolicyDecision.Deny.class);
      String reason = ((PolicyDecision.Deny) decision).reason();
      assertThat(reason).contains("RISK_KEY");
    }

    @Test
    void equalApproveAndDenyAtLeavesNoApprovalBandSoThatSeverityDeniesOutright() {
      UsagePolicy<Object> policy = RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.MODERATE);
      AuthzContext atThreshold = contextWithSeverity(RiskLevel.MODERATE);
      AuthzContext belowThreshold = contextWithSeverity(RiskLevel.LOW);

      assertThat(policy.evaluate(atThreshold, CALL)).isInstanceOf(PolicyDecision.Deny.class);
      assertThat(policy.evaluate(belowThreshold, CALL)).isEqualTo(new PolicyDecision.Allow());
    }
  }
}
