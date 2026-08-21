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
    void very_low_likelihood_and_very_low_impact_combine_to_very_low() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.VERY_LOW, RiskLevel.VERY_LOW, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.VERY_LOW);
    }

    @Test
    void moderate_likelihood_and_moderate_impact_combine_to_moderate() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.MODERATE, RiskLevel.MODERATE, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.MODERATE);
    }

    @Test
    void high_likelihood_and_high_impact_combine_to_high() {
      RiskAssessment assessment = new RiskAssessment(RiskLevel.HIGH, RiskLevel.HIGH, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void very_high_likelihood_and_high_impact_combine_to_very_high() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.VERY_HIGH, RiskLevel.HIGH, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.VERY_HIGH);
    }

    @Test
    void high_likelihood_and_very_high_impact_combine_to_very_high() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.HIGH, RiskLevel.VERY_HIGH, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.VERY_HIGH);
    }

    @Test
    void very_low_likelihood_and_very_high_impact_combine_to_low() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.VERY_LOW, RiskLevel.VERY_HIGH, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void very_high_likelihood_and_very_low_impact_combine_to_very_low() {
      RiskAssessment assessment =
          new RiskAssessment(RiskLevel.VERY_HIGH, RiskLevel.VERY_LOW, List.of());

      assertThat(assessment.severity()).isEqualTo(RiskLevel.VERY_LOW);
    }
  }
}
