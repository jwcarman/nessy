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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * Gating a call on how bad it would be.
 *
 * <p>Three bands, and the interesting one is the middle: an assessment that is neither safe enough
 * to wave through nor bad enough to refuse is what a person is for.
 */
@DisplayName("Gating a call on its risk")
class RiskTest {

  private static ApprovalRequest asking() {
    return new ApprovalRequest(
        AgentType.of("ops"),
        AgentId.of("prod-eu"),
        TurnId.of("turn-1"),
        CallId.of("c1"),
        "prune_images",
        JsonNodeFactory.instance.objectNode(),
        "docker image prune -af",
        Instant.EPOCH,
        () -> ReplyToken.of("nowhere"));
  }

  /** An approver that records whether it was consulted at all. */
  private static Approver counting(AtomicInteger asked, ApprovalResult answer) {
    return request -> {
      asked.incrementAndGet();
      return Awaited.ready(answer);
    };
  }

  private static Approver gate(RiskLevel assessed, Approver desk) {
    return Risk.assessing(
            RiskAssessor.always(
                new RiskAssessment(
                    Likelihood.MODERATE, Impact.MODERATE, assessed, java.util.Set.of())))
        .approvingBelow(RiskLevel.MODERATE)
        .denyingAtOrAbove(RiskLevel.VERY_HIGH)
        .otherwiseAsking(desk);
  }

  @Nested
  class BelowTheFloor {

    @Test
    void it_is_approved_without_troubling_anybody() {
      AtomicInteger asked = new AtomicInteger();

      Awaited<ApprovalResult> answer =
          gate(RiskLevel.LOW, counting(asked, ApprovalResult.denied("should not be reached")))
              .approve(asking());

      assertThat(answer).isEqualTo(Awaited.ready(ApprovalResult.approved()));
      assertThat(asked).hasValue(0);
    }
  }

  @Nested
  class AtOrAboveTheCeiling {

    @Test
    void it_is_denied_without_troubling_anybody() {
      AtomicInteger asked = new AtomicInteger();

      Awaited<ApprovalResult> answer =
          gate(RiskLevel.VERY_HIGH, counting(asked, ApprovalResult.approved())).approve(asking());

      assertThat(answer)
          .isInstanceOfSatisfying(
              Awaited.Ready.class,
              ready -> assertThat(ready.result()).isInstanceOf(ApprovalResult.Denied.class));
      assertThat(asked).as("nobody is asked about a call that is refused outright").hasValue(0);
    }

    @Test
    @DisplayName("the denial names the severity, so a reader knows which threshold bit")
    void the_reason_says_what_was_assessed() {
      Awaited<ApprovalResult> answer =
          gate(RiskLevel.VERY_HIGH, Approver.always()).approve(asking());

      ApprovalResult result = ((Awaited.Ready<ApprovalResult>) answer).result();
      assertThat(((ApprovalResult.Denied) result).reason())
          .contains("VERY_HIGH")
          .contains("refuses");
    }
  }

  @Nested
  class InBetween {

    @Test
    @DisplayName("the middle band is what a person is for")
    void it_is_put_to_the_approver_that_was_named() {
      AtomicInteger asked = new AtomicInteger();

      Awaited<ApprovalResult> answer =
          gate(RiskLevel.HIGH, counting(asked, ApprovalResult.approved())).approve(asking());

      assertThat(asked).hasValue(1);
      assertThat(answer).isEqualTo(Awaited.ready(ApprovalResult.approved()));
    }

    @Test
    @DisplayName("whoever is asked can read WHY, which is the difference from an interruption")
    void the_assessment_is_recorded_on_the_request_before_anyone_is_asked() {
      ApprovalRequest request = asking();

      gate(RiskLevel.HIGH, Approver.always()).approve(request);

      assertThat(request.fact(Risk.FACT)).map(node -> node.asText()).contains("HIGH");
    }
  }

  @Nested
  class ThresholdsThatContradict {

    @Test
    @DisplayName("a ceiling below the floor would deny calls it also approves")
    void they_are_refused_when_they_are_configured() {
      Risk.Denying half =
          Risk.assessing(RiskAssessor.always(RiskAssessment.of(Likelihood.LOW, Impact.LOW)))
              .approvingBelow(RiskLevel.HIGH);

      assertThatThrownBy(() -> half.denyingAtOrAbove(RiskLevel.LOW))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("below");
    }

    @Test
    @DisplayName("equal thresholds are allowed: everything is either approved or denied")
    void a_gate_with_no_middle_band_never_asks_anybody() {
      AtomicInteger asked = new AtomicInteger();
      Approver gate =
          Risk.assessing(
                  RiskAssessor.always(
                      new RiskAssessment(
                          Likelihood.MODERATE,
                          Impact.MODERATE,
                          RiskLevel.MODERATE,
                          java.util.Set.of())))
              .approvingBelow(RiskLevel.MODERATE)
              .denyingAtOrAbove(RiskLevel.MODERATE)
              .otherwiseAsking(counting(asked, ApprovalResult.approved()));

      Awaited<ApprovalResult> answer = gate.approve(asking());

      assertThat(asked).hasValue(0);
      assertThat(((Awaited.Ready<ApprovalResult>) answer).result())
          .isInstanceOf(ApprovalResult.Denied.class);
    }
  }

  @Nested
  class TheAssessor {

    @Test
    @DisplayName("it sees the request, so a policy can turn on the tool and what it was asked")
    void it_is_given_the_whole_question() {
      Risk.assessing(
              request -> {
                assertThat(request.toolName()).isEqualTo("prune_images");
                assertThat(request.action()).isEqualTo("docker image prune -af");
                return RiskAssessment.of(Likelihood.LOW, Impact.LOW);
              })
          .approvingBelow(RiskLevel.MODERATE)
          .denyingAtOrAbove(RiskLevel.VERY_HIGH)
          .otherwiseAsking(Approver.always())
          .approve(asking());
    }
  }
}
