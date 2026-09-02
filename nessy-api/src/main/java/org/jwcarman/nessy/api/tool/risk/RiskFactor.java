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

/**
 * A typed, open-vocabulary reason behind a {@link RiskAssessment} (action-wave spec §2) —
 * deliberately unlike {@link Key}: two modules that both say "destructive" mean the same factor, so
 * equality is by name (record default), not by identity. {@link RiskFactors} seeds the starting
 * vocabulary; an org's own factor is just another {@code RiskFactor}, never a sealed grammar.
 *
 * @param name the factor's name, e.g. {@code "destructive"}
 */
public record RiskFactor(String name) {

  public RiskFactor {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }

  @Override
  public String toString() {
    return name;
  }
}
