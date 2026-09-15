package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.approval.risk.Impact;
import org.jwcarman.nessy.approval.risk.Likelihood;
import org.jwcarman.nessy.approval.risk.Risk;
import org.jwcarman.nessy.approval.risk.RiskAssessment;
import org.jwcarman.nessy.approval.risk.RiskLevel;
import tools.jackson.databind.JsonNode;

@DisplayName("The watchman's risk appetite")
class WatchmanRiskTest {

  private static final ToolName PRUNE = new ToolName("prune_images");

  private static ApprovalRequest pruning() {
    return new ApprovalRequest(
        Watchman.TYPE,
        Watchman.AGENT,
        new TurnId(1),
        new CallId("c1"),
        PRUNE,
        "{}",
        "docker image prune -af",
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(3600),
        new ReplyToken("nowhere"));
  }

  @Test
  @DisplayName("pruning images lands in the middle band, so a person decides")
  void the_assessed_level_is_neither_waved_through_nor_refused() {
    RiskLevel assessed = RiskAssessment.of(Likelihood.HIGH, Impact.MODERATE).risk();
    assertThat(assessed)
        .isEqualTo(RiskLevel.MODERATE)
        .as("not below the approving threshold, so it is not waved through")
        .isGreaterThanOrEqualTo(RiskLevel.MODERATE)
        .as("not at the denying threshold, so nobody is refused without being asked")
        .isLessThan(RiskLevel.VERY_HIGH);
  }

  @Test
  @DisplayName("the gate actually reaches the desk -- the middle band is not a comment")
  void pruning_images_is_put_to_a_person() {
    AtomicBoolean asked = new AtomicBoolean();
    Approver desk =
        request -> {
          asked.set(true);
          return Awaited.deferred();
        };
    var answer = WatchmanConfiguration.gatedOnRisk(PRUNE, desk).approve(pruning());
    assertThat(asked).as("a soak that never parks cannot tell you this").isTrue();
    assertThat(answer).isInstanceOf(Awaited.Deferred.class);
  }

  @Test
  @DisplayName("and the person is told why they are being asked")
  void the_assessment_reaches_whoever_answers() {
    ApprovalRequest request = pruning();
    WatchmanConfiguration.gatedOnRisk(PRUNE, req -> Awaited.deferred()).approve(request);
    assertThat(request.fact(Risk.FACT)).map(JsonNode::asString).contains("MODERATE");
  }

  @Test
  @DisplayName("the question a person is shown names the command they are consenting to")
  void the_description_is_the_command() {
    assertThat(pruning().action()).isEqualTo("docker image prune -af");
  }
}
