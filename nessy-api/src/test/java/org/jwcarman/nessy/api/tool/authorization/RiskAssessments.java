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

/**
 * A test-only fixture for {@link RiskRulesTest}: a {@link RiskAssessment} whose {@link
 * RiskAssessment#risk()} equals the requested {@link RiskLevel}, derived from the severity matrix
 * transcribed in {@link RiskAssessmentTest}.
 */
final class RiskAssessments {

  private RiskAssessments() {}

  static RiskAssessment at(RiskLevel level) {
    return switch (level) {
      case VERY_LOW -> RiskAssessment.of(Likelihood.VERY_LOW, Impact.VERY_LOW);
      case LOW -> RiskAssessment.of(Likelihood.LOW, Impact.LOW);
      case MODERATE -> RiskAssessment.of(Likelihood.MODERATE, Impact.MODERATE);
      case HIGH -> RiskAssessment.of(Likelihood.HIGH, Impact.HIGH);
      case VERY_HIGH -> RiskAssessment.of(Likelihood.VERY_HIGH, Impact.VERY_HIGH);
    };
  }
}
