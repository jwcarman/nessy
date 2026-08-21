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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskAssessmentTest {

  @Nested
  class Construction_guards {

    @Test
    void rejectsANullLikelihood() {
      assertThatThrownBy(() -> new RiskAssessment(null, RiskLevel.LOW, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("likelihood");
    }

    @Test
    void rejectsANullImpact() {
      assertThatThrownBy(() -> new RiskAssessment(RiskLevel.LOW, null, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("impact");
    }

    @Test
    void rejectsNullFactors() {
      assertThatThrownBy(() -> new RiskAssessment(RiskLevel.LOW, RiskLevel.LOW, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("factors");
    }

    @Test
    void copiesTheFactorsListSoTheCallerCannotMutateItAfterConstruction() {
      List<String> factors = new ArrayList<>();
      factors.add(RiskFactors.DESTRUCTIVE);
      RiskAssessment assessment = new RiskAssessment(RiskLevel.LOW, RiskLevel.LOW, factors);

      factors.add(RiskFactors.IRREVERSIBLE);

      assertThat(assessment.factors()).containsExactly(RiskFactors.DESTRUCTIVE);
    }
  }

  @Nested
  class Severity_matrix_boundary_cells {

    @Test
    void veryLowLikelihoodAndVeryLowImpactCombineToVeryLow() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.VERY_LOW, RiskLevel.VERY_LOW, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.VERY_LOW);
    }

    @Test
    void moderateLikelihoodAndModerateImpactCombineToModerate() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.MODERATE, RiskLevel.MODERATE, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.MODERATE);
    }

    @Test
    void highLikelihoodAndHighImpactCombineToHigh() {
      RiskAssessment assessment = new RiskAssessment(RiskLevel.HIGH, RiskLevel.HIGH, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void veryHighLikelihoodAndHighImpactCombineToVeryHigh() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.VERY_HIGH, RiskLevel.HIGH, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.VERY_HIGH);
    }

    @Test
    void highLikelihoodAndVeryHighImpactCombineToVeryHigh() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.HIGH, RiskLevel.VERY_HIGH, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.VERY_HIGH);
    }

    @Test
    void veryLowLikelihoodAndVeryHighImpactCombineToLow() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.VERY_LOW, RiskLevel.VERY_HIGH, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void veryHighLikelihoodAndVeryLowImpactCombineToVeryLow() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.VERY_HIGH, RiskLevel.VERY_LOW, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.VERY_LOW);
    }
  }

  @Nested
  class The_entire_severity_matrix {

    /**
     * Every cell of the NIST SP 800-30 Table I-2 shaped matrix (task brief's normative table, rows
     * likelihood, columns impact) — transcribed from the spec, not read back from {@link
     * RiskAssessment#severity()}'s own implementation.
     */
    @ParameterizedTest(name = "likelihood={0}, impact={1} -> severity={2}")
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
        RiskLevel likelihood, RiskLevel impact, RiskLevel expectedSeverity) {
      RiskAssessment assessment = new RiskAssessment(likelihood, impact, List.of());

      assertThat(assessment.severity()).isEqualTo(expectedSeverity);
    }
  }
}
