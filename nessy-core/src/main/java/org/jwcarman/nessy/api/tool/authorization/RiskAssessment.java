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

import java.util.List;
import java.util.Objects;

/**
 * The standard risk shape (action-wave spec §2): a likelihood, an impact, and the open-vocabulary
 * factors that justified them. Not a mandate — an org with its own risk model deposits its own type
 * under its own {@link Key}; this is the opinionated default that makes {@link RiskPolicies}
 * shippable out of the box.
 *
 * @param likelihood how probable the assessed effect is
 * @param impact how severe the assessed effect would be if it occurred
 * @param factors the open-vocabulary reasons behind the assessment (see {@link RiskFactors})
 */
public record RiskAssessment(RiskLevel likelihood, RiskLevel impact, List<String> factors) {

  /**
   * NIST SP 800-30 Table I-2's qualitative combination matrix, rows likelihood and columns impact —
   * indexed here by each level's declaration ordinal, since {@link RiskLevel}'s declaration order
   * is severity order.
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
    Objects.requireNonNull(factors, "factors must not be null");
    factors = List.copyOf(factors);
  }

  /** The NIST-style qualitative combination of {@link #likelihood} and {@link #impact}. */
  public RiskLevel severity() {
    return SEVERITY_MATRIX[likelihood.ordinal()][impact.ordinal()];
  }
}
