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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.risk.Impact;
import org.jwcarman.nessy.api.tool.risk.Likelihood;
import org.jwcarman.nessy.api.tool.risk.RiskAssessment;
import org.jwcarman.nessy.api.tool.risk.RiskLevel;

/**
 * What this box's risk appetite actually does to its gated tool.
 *
 * <p>The thresholds and the assessment are separate decisions, and only their COMBINATION says
 * whether anybody gets woken up. A comment claiming a matrix value is a comment that will
 * eventually be wrong, so the claim lives here instead.
 */
@DisplayName("The watchman's risk appetite")
class WatchmanRiskTest {

  private static ApprovalRequest pruning() {
    return new ApprovalRequest(
        AgentType.of("watchman"),
        AgentId.of("house"),
        TurnId.of("turn-1"),
        CallId.of("c1"),
        "prune_images",
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
        "docker image prune -af",
        Instant.EPOCH,
        () -> ReplyToken.of("nowhere"));
  }

  @Test
  @DisplayName("pruning images lands in the middle band, so a person decides")
  void the_assessed_level_is_neither_waved_through_nor_refused() {
    RiskLevel assessed = RiskAssessment.of(Likelihood.HIGH, Impact.MODERATE).risk();

    assertThat(assessed).isEqualTo(RiskLevel.MODERATE);
    assertThat(assessed)
        .as("not below the approving threshold, so it is not waved through")
        .isGreaterThanOrEqualTo(RiskLevel.MODERATE);
    assertThat(assessed)
        .as("not at the denying threshold, so nobody is refused without being asked")
        .isLessThan(RiskLevel.VERY_HIGH);
  }

  @Test
  @DisplayName("the gate actually reaches the desk — the middle band is not a comment")
  void pruning_images_is_put_to_a_person() {
    java.util.concurrent.atomic.AtomicBoolean asked =
        new java.util.concurrent.atomic.AtomicBoolean();
    org.jwcarman.nessy.api.tool.Approver desk =
        request -> {
          asked.set(true);
          return org.jwcarman.nessy.api.Awaited.deferred(Instant.EPOCH.plusSeconds(3600));
        };

    var answer = WatchmanConfiguration.gatedOnRisk("prune_images", desk).approve(pruning());

    assertThat(asked).as("a soak that never parks cannot tell you this").isTrue();
    assertThat(answer).isInstanceOf(org.jwcarman.nessy.api.Awaited.Deferred.class);
  }

  @Test
  @DisplayName("and the person is told why they are being asked")
  void the_assessment_reaches_whoever_answers() {
    ApprovalRequest request = pruning();

    WatchmanConfiguration.gatedOnRisk(
            "prune_images", req -> org.jwcarman.nessy.api.Awaited.deferred(Instant.EPOCH))
        .approve(request);

    assertThat(request.fact(org.jwcarman.nessy.api.tool.risk.Risk.FACT))
        .map(node -> node.asText())
        .contains("MODERATE");
  }

  @Test
  @DisplayName("the question a person is shown names the command they are consenting to")
  void the_description_is_the_command() {
    assertThat(pruning().action()).isEqualTo("docker image prune -af");
  }
}
