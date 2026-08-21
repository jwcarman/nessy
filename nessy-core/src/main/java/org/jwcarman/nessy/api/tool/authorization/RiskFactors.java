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
 * Seed vocabulary for {@link RiskAssessment#factors()} — the closest thing agent tooling has to a
 * standard risk vocabulary, drawn from MCP tool annotations plus nessy's own additions. Factors are
 * open strings, deliberately: this is a starting vocabulary, not a sealed grammar — an org adds its
 * own alongside these.
 */
public final class RiskFactors {

  public static final String DESTRUCTIVE = "destructive";
  public static final String IRREVERSIBLE = "irreversible";
  public static final String EXTERNAL_WORLD = "external-world";
  public static final String READ_ONLY = "read-only";
  public static final String SPENDS_MONEY = "spends-money";
  public static final String TOUCHES_PII = "touches-pii";

  private RiskFactors() {}
}
