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

/**
 * Seed vocabulary for {@link RiskAssessment#factors()} — the closest thing agent tooling has to a
 * standard risk vocabulary, drawn from MCP tool annotations plus nessy's own additions. Factors are
 * open {@link RiskFactor} values, deliberately: this is a starting vocabulary, not a sealed grammar
 * — an org adds its own {@code RiskFactor}s alongside these.
 */
public final class RiskFactors {

  public static final RiskFactor DESTRUCTIVE = new RiskFactor("destructive");
  public static final RiskFactor IRREVERSIBLE = new RiskFactor("irreversible");
  public static final RiskFactor EXTERNAL_WORLD = new RiskFactor("external-world");
  public static final RiskFactor READ_ONLY = new RiskFactor("read-only");
  public static final RiskFactor SPENDS_MONEY = new RiskFactor("spends-money");
  public static final RiskFactor TOUCHES_PII = new RiskFactor("touches-pii");

  private RiskFactors() {}
}
