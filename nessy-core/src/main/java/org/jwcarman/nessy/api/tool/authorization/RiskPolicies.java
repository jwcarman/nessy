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
import java.util.Optional;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.UsagePolicy;

/**
 * Canonical {@link UsagePolicy} factories that judge {@link RiskAssessment#severity()} (action-wave
 * spec §2) — the one-line policy every deployment actually wants, composable with any org's own
 * risk-assessing {@link Enricher}.
 */
public final class RiskPolicies {

  private RiskPolicies() {}

  /**
   * Reads {@link AuthzContext#RISK_KEY} and judges by severity against the two thresholds: severity
   * below {@code approveAt} allows, from {@code approveAt} up to (but below) {@code denyAt}
   * requires approval, and {@code denyAt} or above denies naming the severity and the threshold. An
   * absent assessment fails closed with a {@link PolicyDecision.Deny} naming the empty slot.
   *
   * <p>This reads the context on every call — deliberately not a {@link UsagePolicy.Static}, since
   * its verdict depends on whatever the risk-assessing enricher deposited.
   *
   * <p>{@code approveAt == denyAt} is a legal, if unusual, configuration: it collapses the approval
   * band to nothing, so a severity at or above that single level denies outright rather than ever
   * deferring to an approver.
   *
   * @throws IllegalArgumentException if {@code approveAt} is more severe than {@code denyAt}
   */
  public static UsagePolicy<Object> threshold(RiskLevel approveAt, RiskLevel denyAt) {
    Objects.requireNonNull(approveAt, "approveAt must not be null");
    Objects.requireNonNull(denyAt, "denyAt must not be null");
    if (approveAt.compareTo(denyAt) > 0) {
      throw new IllegalArgumentException("approveAt must not exceed denyAt");
    }
    return new ThresholdPolicy(approveAt, denyAt);
  }

  /**
   * The policy {@link #threshold(RiskLevel, RiskLevel)} returns — a named class, not a bare lambda,
   * so {@link AuthorizationReport} reports it as {@code policy (ThresholdPolicy)} rather than an
   * unreadable synthetic lambda class name (design of record 2026-08-16-authorization §8).
   * Package-private: {@link #threshold(RiskLevel, RiskLevel)} is the only supported way to obtain
   * one.
   */
  static final class ThresholdPolicy implements UsagePolicy<Object> {

    private final RiskLevel approveAt;
    private final RiskLevel denyAt;

    ThresholdPolicy(RiskLevel approveAt, RiskLevel denyAt) {
      this.approveAt = approveAt;
      this.denyAt = denyAt;
    }

    @Override
    public PolicyDecision evaluate(AuthzContext context, Object action) {
      Optional<RiskAssessment> assessment = context.risk();
      if (assessment.isEmpty()) {
        return new PolicyDecision.Deny("no risk assessment deposited under RISK_KEY");
      }
      RiskLevel severity = assessment.get().severity();
      if (severity.compareTo(approveAt) < 0) {
        return new PolicyDecision.Allow();
      }
      if (severity.compareTo(denyAt) < 0) {
        return new PolicyDecision.RequireApproval();
      }
      return new PolicyDecision.Deny(
          "risk severity " + severity + " meets or exceeds threshold " + denyAt);
    }
  }
}
