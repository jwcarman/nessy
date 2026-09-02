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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskAssessmentTest {

  @Nested
  class Construction_guards {

    @Test
    void rejectsANullLikelihood() {
      assertThatThrownBy(() -> new RiskAssessment(null, Impact.LOW, RiskLevel.LOW, Set.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("likelihood");
    }

    @Test
    void rejectsANullImpact() {
      assertThatThrownBy(() -> new RiskAssessment(Likelihood.LOW, null, RiskLevel.LOW, Set.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("impact");
    }

    @Test
    void rejectsANullRisk() {
      assertThatThrownBy(() -> new RiskAssessment(Likelihood.LOW, Impact.LOW, null, Set.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("risk");
    }

    @Test
    void rejectsNullFactors() {
      assertThatThrownBy(() -> new RiskAssessment(Likelihood.LOW, Impact.LOW, RiskLevel.LOW, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("factors");
    }

    @Test
    void copiesTheFactorsSetSoTheCallerCannotMutateItAfterConstruction() {
      Set<RiskFactor> factors = new LinkedHashSet<>();
      factors.add(RiskFactors.DESTRUCTIVE);
      RiskAssessment assessment =
          new RiskAssessment(Likelihood.LOW, Impact.LOW, RiskLevel.LOW, factors);

      factors.add(RiskFactors.IRREVERSIBLE);

      assertThat(assessment.factors()).containsExactly(RiskFactors.DESTRUCTIVE);
    }

    @Test
    void dedupsDuplicateFactorsViaSetCopyOf() {
      Set<RiskFactor> factors = new LinkedHashSet<>();
      factors.add(new RiskFactor("destructive"));
      factors.add(new RiskFactor("destructive"));

      RiskAssessment assessment =
          new RiskAssessment(Likelihood.LOW, Impact.LOW, RiskLevel.LOW, factors);

      assertThat(assessment.factors()).containsExactly(RiskFactors.DESTRUCTIVE);
    }

    @Test
    void theCanonicalConstructorStoresAContradictingOverrideVerbatim() {
      RiskAssessment assessment =
          new RiskAssessment(Likelihood.VERY_LOW, Impact.VERY_LOW, RiskLevel.VERY_HIGH, Set.of());

      assertThat(assessment.risk()).isEqualTo(RiskLevel.VERY_HIGH);
    }
  }

  @Nested
  class Risk_factor_value_equality {

    @Test
    void twoRiskFactorsWithTheSameNameAreEqual() {
      assertThat(new RiskFactor("destructive")).isEqualTo(new RiskFactor("destructive"));
    }

    @Test
    void twoRiskFactorsWithDifferentNamesAreNotEqual() {
      assertThat(new RiskFactor("destructive")).isNotEqualTo(new RiskFactor("irreversible"));
    }
  }

  @Nested
  class Of_severity_matrix_boundary_cells {

    @Test
    void veryLowLikelihoodAndVeryLowImpactCombineToVeryLow() {
      RiskAssessment assessment = RiskAssessment.of(Likelihood.VERY_LOW, Impact.VERY_LOW);

      assertThat(assessment.risk()).isEqualTo(RiskLevel.VERY_LOW);
    }

    @Test
    void moderateLikelihoodAndModerateImpactCombineToModerate() {
      RiskAssessment assessment = RiskAssessment.of(Likelihood.MODERATE, Impact.MODERATE);

      assertThat(assessment.risk()).isEqualTo(RiskLevel.MODERATE);
    }

    @Test
    void highLikelihoodAndHighImpactCombineToHigh() {
      RiskAssessment assessment = RiskAssessment.of(Likelihood.HIGH, Impact.HIGH);

      assertThat(assessment.risk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void veryHighLikelihoodAndHighImpactCombineToVeryHigh() {
      RiskAssessment assessment = RiskAssessment.of(Likelihood.VERY_HIGH, Impact.HIGH);

      assertThat(assessment.risk()).isEqualTo(RiskLevel.VERY_HIGH);
    }

    @Test
    void highLikelihoodAndVeryHighImpactCombineToVeryHigh() {
      RiskAssessment assessment = RiskAssessment.of(Likelihood.HIGH, Impact.VERY_HIGH);

      assertThat(assessment.risk()).isEqualTo(RiskLevel.VERY_HIGH);
    }

    @Test
    void veryLowLikelihoodAndVeryHighImpactCombineToLow() {
      RiskAssessment assessment = RiskAssessment.of(Likelihood.VERY_LOW, Impact.VERY_HIGH);

      assertThat(assessment.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void veryHighLikelihoodAndVeryLowImpactCombineToVeryLow() {
      RiskAssessment assessment = RiskAssessment.of(Likelihood.VERY_HIGH, Impact.VERY_LOW);

      assertThat(assessment.risk()).isEqualTo(RiskLevel.VERY_LOW);
    }
  }

  @Nested
  class The_entire_severity_matrix {

    /**
     * Every cell of the NIST SP 800-30 Table I-2 shaped matrix (task brief's normative table, rows
     * likelihood, columns impact) — transcribed from the spec, not read back from {@link
     * RiskAssessment#of}'s own implementation.
     */
    @ParameterizedTest(name = "likelihood={0}, impact={1} -> risk={2}")
    @CsvSource({
      "VERY_LOW, VERY_LOW,  VERY_LOW",
      "VERY_LOW, LOW,       VERY_LOW",
      "VERY_LOW, MODERATE,  VERY_LOW",
      "VERY_LOW, HIGH,      LOW",
      "VERY_LOW, VERY_HIGH, LOW",
      "LOW,      VERY_LOW,  VERY_LOW",
      "LOW,      LOW,       LOW",
      "LOW,      MODERATE,  LOW",
      "LOW,      HIGH,      LOW",
      "LOW,      VERY_HIGH, MODERATE",
      "MODERATE, VERY_LOW,  VERY_LOW",
      "MODERATE, LOW,       LOW",
      "MODERATE, MODERATE,  MODERATE",
      "MODERATE, HIGH,      MODERATE",
      "MODERATE, VERY_HIGH, HIGH",
      "HIGH,     VERY_LOW,  VERY_LOW",
      "HIGH,     LOW,       LOW",
      "HIGH,     MODERATE,  MODERATE",
      "HIGH,     HIGH,      HIGH",
      "HIGH,     VERY_HIGH, VERY_HIGH",
      "VERY_HIGH, VERY_LOW,  VERY_LOW",
      "VERY_HIGH, LOW,       LOW",
      "VERY_HIGH, MODERATE,  MODERATE",
      "VERY_HIGH, HIGH,      VERY_HIGH",
      "VERY_HIGH, VERY_HIGH, VERY_HIGH",
    })
    void combinesLikelihoodAndImpactAccordingToTheNormativeMatrix(
        Likelihood likelihood, Impact impact, RiskLevel expectedRisk) {
      RiskAssessment assessment = RiskAssessment.of(likelihood, impact);

      assertThat(assessment.risk()).isEqualTo(expectedRisk);
    }
  }
}
