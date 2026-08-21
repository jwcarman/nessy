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
 * NIST SP 800-30's five qualitative risk levels — declaration order is severity order, so ordinal
 * comparison ({@link #compareTo}) is a legitimate way to compare two levels. This is solely the
 * conclusion/severity axis (action-wave spec §2, amended 2026-08-21): the assessor's stored
 * conclusion, whether derived from {@link Likelihood} and {@link Impact} via {@link
 * RiskAssessment#of} or deliberately overridden via {@link RiskAssessment}'s canonical constructor.
 */
public enum RiskLevel {

  /** Very unlikely / negligible. */
  VERY_LOW,

  /** Unlikely / limited. */
  LOW,

  /** Somewhat likely / serious. */
  MODERATE,

  /** Likely / severe. */
  HIGH,

  /** Almost certain / catastrophic. */
  VERY_HIGH
}
