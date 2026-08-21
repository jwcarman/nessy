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
import java.util.Set;

/**
 * The standard risk shape (action-wave spec §2, amended 2026-08-21): a likelihood, an impact, the
 * assessor's stored conclusion, and the open-vocabulary factors that justified it. Not a mandate —
 * an org with its own risk model deposits its own type under its own {@link Key}; this is the
 * opinionated default that makes {@link RiskPolicies} shippable out of the box.
 *
 * <p>The canonical constructor is the explicit-override door: it stores {@code risk} verbatim, so
 * an org's assessor may conclude a level the standard combination would not — deliberate elevation
 * is legitimate. {@link #of} is the shipped opinion: it derives {@code risk} from the NIST-style
 * combination of {@code likelihood} and {@code impact}.
 *
 * @param likelihood how probable the assessed action is
 * @param impact how severe the assessed action would be if it occurred
 * @param risk the assessor's stored conclusion — verbatim, even if it disagrees with what {@link
 *     #of} would have derived from {@code likelihood} and {@code impact}
 * @param factors the open-vocabulary reasons behind the assessment (see {@link RiskFactors})
 */
public record RiskAssessment(
    Likelihood likelihood, Impact impact, RiskLevel risk, Set<RiskFactor> factors) {

  /**
   * NIST SP 800-30 Table I-2's qualitative combination matrix, rows likelihood and columns impact —
   * indexed here by each level's declaration ordinal, since {@link Likelihood}'s and {@link
   * Impact}'s declaration order is severity order.
   */
  private static final RiskLevel[][] SEVERITY_MATRIX = {
    // impact:  VL              L               M               H               VH
    /* VL */ {
      RiskLevel.VERY_LOW, RiskLevel.VERY_LOW, RiskLevel.VERY_LOW, RiskLevel.LOW, RiskLevel.LOW
    },
    /* L  */ {RiskLevel.VERY_LOW, RiskLevel.LOW, RiskLevel.LOW, RiskLevel.LOW, RiskLevel.MODERATE},
    /* M  */ {
      RiskLevel.VERY_LOW, RiskLevel.LOW, RiskLevel.MODERATE, RiskLevel.MODERATE, RiskLevel.HIGH
    },
    /* H  */ {
      RiskLevel.VERY_LOW, RiskLevel.LOW, RiskLevel.MODERATE, RiskLevel.HIGH, RiskLevel.VERY_HIGH
    },
    /* VH */ {
      RiskLevel.VERY_LOW,
      RiskLevel.LOW,
      RiskLevel.MODERATE,
      RiskLevel.VERY_HIGH,
      RiskLevel.VERY_HIGH
    }
  };

  public RiskAssessment {
    Objects.requireNonNull(likelihood, "likelihood must not be null");
    Objects.requireNonNull(impact, "impact must not be null");
    Objects.requireNonNull(risk, "risk must not be null");
    Objects.requireNonNull(factors, "factors must not be null");
    factors = Set.copyOf(factors);
  }

  /**
   * Derives {@code risk} from {@code likelihood} and {@code impact} via the NIST-style combination
   * matrix — the shipped opinion, for callers who don't need to override the assessor's conclusion.
   */
  public static RiskAssessment of(Likelihood likelihood, Impact impact, RiskFactor... factors) {
    Objects.requireNonNull(likelihood, "likelihood must not be null");
    Objects.requireNonNull(impact, "impact must not be null");
    RiskLevel derived = SEVERITY_MATRIX[likelihood.ordinal()][impact.ordinal()];
    return new RiskAssessment(likelihood, impact, derived, Set.of(factors));
  }
}
