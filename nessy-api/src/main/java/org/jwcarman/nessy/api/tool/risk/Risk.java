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

import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/**
 * Gating a call on how risky it is.
 *
 * <pre>{@code
 * binding.approver(
 *     Risk.assessing(RiskAssessor.always(RiskAssessment.of(Likelihood.HIGH, Impact.HIGH)))
 *         .approvingBelow(RiskLevel.MODERATE)
 *         .denyingAtOrAbove(RiskLevel.VERY_HIGH)
 *         .otherwiseAsking(desk));
 * }</pre>
 *
 * <p>Three bands: below the approving threshold answers yes on the spot, at or above the denying
 * threshold answers no and names the severity, and everything between goes to the approver you
 * named — normally a person.
 *
 * <p><b>Why this is an {@code Approver} and not a rule engine.</b> An earlier design had a separate
 * {@code Rule} vocabulary with its own three outcomes, because grants carried a list of enrichers
 * that something had to sequence. {@code Approver} already has those three outcomes — approved,
 * denied, and {@link Awaited#deferred} for "ask somebody" — so a rule that cannot decide is just an
 * approver that delegates to the next one. Composition needs no new noun.
 *
 * <p><b>The assessment is recorded on the request.</b> Whoever is asked can read it under {@link
 * #FACT} and show a person WHY they are being asked, which is the difference between a prompt and
 * an interruption.
 */
public final class Risk {

  /** Where the assessment lands on the request, for whoever is asked next. */
  public static final String FACT = "risk";

  private Risk() {}

  /** Names the assessor. The thresholds come next. */
  public static Approving assessing(RiskAssessor assessor) {
    return new Approving(Objects.requireNonNull(assessor, "assessor must not be null"));
  }

  /** An assessor, waiting to be told what counts as safe. */
  public record Approving(RiskAssessor assessor) {

    /** Anything strictly below this is approved without asking. */
    public Denying approvingBelow(RiskLevel approveBelow) {
      return new Denying(
          assessor, Objects.requireNonNull(approveBelow, "approveBelow must not be null"));
    }
  }

  /** An assessor and a floor, waiting to be told what counts as unacceptable. */
  public record Denying(RiskAssessor assessor, RiskLevel approveBelow) {

    /** Anything at or above this is denied outright, without troubling anybody. */
    public Asking denyingAtOrAbove(RiskLevel denyAtOrAbove) {
      RiskLevel deny = Objects.requireNonNull(denyAtOrAbove, "denyAtOrAbove must not be null");
      if (deny.compareTo(approveBelow) < 0) {
        throw new IllegalArgumentException(
            "denyingAtOrAbove(%s) is below approvingBelow(%s), which would deny calls it also"
                    .formatted(deny, approveBelow)
                + " approves");
      }
      return new Asking(assessor, approveBelow, deny);
    }
  }

  /** Both thresholds set. What remains is who answers the middle band. */
  public record Asking(RiskAssessor assessor, RiskLevel approveBelow, RiskLevel denyAtOrAbove) {

    /**
     * The approver that answers everything in between — normally a desk that defers to a person.
     *
     * @return an ordinary {@link Approver}, so this composes wherever one is accepted
     */
    public Approver otherwiseAsking(Approver next) {
      Objects.requireNonNull(next, "next must not be null");
      return request -> {
        RiskAssessment assessment = assessor.assess(request);
        // Recorded before anyone is asked, so whoever answers can say why they were asked.
        request.fact(Risk.FACT, assessment.risk().name());
        RiskLevel level = assessment.risk();
        if (level.compareTo(approveBelow) < 0) {
          return Awaited.ready(ApprovalResult.approved());
        }
        if (level.compareTo(denyAtOrAbove) >= 0) {
          return Awaited.ready(
              ApprovalResult.denied(
                  "risk assessed %s, at or above the %s this tool refuses"
                      .formatted(level, denyAtOrAbove)));
        }
        return next.approve(request);
      };
    }
  }
}
