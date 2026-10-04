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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("An approver's answer")
class ApprovalResultTest {

  @Nested
  @DisplayName("Naming who decided")
  class NamingWhoDecided {

    @Test
    void approvedBy_carries_who_decided() {
      assertThat(ApprovalResult.approvedBy("u_carol").decidedBy()).contains("u_carol");
    }

    @Test
    void deniedBy_carries_who_decided_and_the_reason() {
      ApprovalResult result = ApprovalResult.deniedBy("out of hours", "u_dave");

      assertThat(result.decidedBy()).contains("u_dave");
      assertThat(result)
          .isEqualTo(new ApprovalResult.Denied("out of hours", Optional.of("u_dave")));
    }

    @Test
    void an_answer_with_nobody_named_decides_by_no_one() {
      assertThat(ApprovalResult.approved().decidedBy()).isEmpty();
      assertThat(ApprovalResult.denied("no").decidedBy()).isEmpty();
    }
  }

  @Nested
  @DisplayName("Refusing a decidedBy that is not there")
  class RefusingAMissingDecidedBy {

    @Test
    void approvedBy_refuses_null() {
      assertThatThrownBy(() -> ApprovalResult.approvedBy(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("decidedBy must not be null");
    }

    @Test
    void deniedBy_refuses_null() {
      assertThatThrownBy(() -> ApprovalResult.deniedBy("no", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("decidedBy must not be null");
    }

    @Test
    void an_approval_refuses_a_null_optional() {
      assertThatThrownBy(() -> new ApprovalResult.Approved(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("decidedBy must not be null");
    }

    @Test
    void a_denial_refuses_a_null_optional() {
      assertThatThrownBy(() -> new ApprovalResult.Denied("no", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("decidedBy must not be null");
    }
  }

  @Nested
  @DisplayName("Keeping the application's own words, cut only to a safe length")
  class KeepingTheApplicationsOwnWords {

    private static final int CAP = ToolConfig.LINE_CAP;

    @Test
    void a_decider_longer_than_the_cap_is_cut_to_it_and_keeps_its_start() {
      String long1500 = "d".repeat(10) + "x".repeat(1490);
      String expected = long1500.substring(0, CAP - 3) + "...";

      ApprovalResult approved = ApprovalResult.approvedBy(long1500);
      ApprovalResult denied = ApprovalResult.deniedBy("no", long1500);

      assertThat(approved.decidedBy()).contains(expected);
      assertThat(denied.decidedBy()).contains(expected);
      assertThat(expected).hasSize(CAP).startsWith("d".repeat(10));
    }

    @Test
    void the_constructors_cut_a_long_decider_too() {
      String long1500 = "y".repeat(1500);
      String expected = "y".repeat(CAP - 3) + "...";

      assertThat(new ApprovalResult.Approved(Optional.of(long1500)).decidedBy()).contains(expected);
      assertThat(new ApprovalResult.Denied("no", Optional.of(long1500)).decidedBy())
          .contains(expected);
    }

    @Test
    void a_decider_at_the_cap_is_kept_as_given() {
      String atCap = "a".repeat(CAP);

      assertThat(ApprovalResult.approvedBy(atCap).decidedBy()).contains(atCap);
      assertThat(ApprovalResult.deniedBy("no", atCap).decidedBy()).contains(atCap);
    }

    @Test
    void a_blank_decider_is_kept_as_given() {
      assertThat(ApprovalResult.approvedBy("").decidedBy()).contains("");
      assertThat(ApprovalResult.approvedBy("   ").decidedBy()).contains("   ");
      assertThat(ApprovalResult.deniedBy("no", " ").decidedBy()).contains(" ");
    }

    @Test
    void a_decider_with_newlines_is_kept_as_given() {
      String withNewlines = "line one\n\n  line two\r\n\tline three";

      assertThat(ApprovalResult.approvedBy(withNewlines).decidedBy()).contains(withNewlines);
      assertThat(ApprovalResult.deniedBy("no", withNewlines).decidedBy()).contains(withNewlines);
    }

    @Test
    void a_reason_longer_than_the_cap_is_truncated_to_it() {
      String longReason = "r".repeat(5000);

      ApprovalResult.Denied bare = (ApprovalResult.Denied) ApprovalResult.denied(longReason);
      ApprovalResult.Denied named =
          (ApprovalResult.Denied) ApprovalResult.deniedBy(longReason, "u_dave");

      assertThat(bare.reason()).hasSizeLessThanOrEqualTo(CAP).startsWith("r").contains("...");
      assertThat(named.reason()).hasSizeLessThanOrEqualTo(CAP).startsWith("r").contains("...");
    }

    @Test
    void a_short_reason_is_kept_as_given() {
      String reason = "out of hours\nsee the ticket  ";

      ApprovalResult.Denied denied = (ApprovalResult.Denied) ApprovalResult.denied(reason);

      assertThat(denied.reason()).isEqualTo(reason);
    }

    @Test
    void an_empty_reason_is_kept_as_given() {
      ApprovalResult.Denied denied = (ApprovalResult.Denied) ApprovalResult.denied("");

      assertThat(denied.reason()).isEmpty();
    }
  }
}
