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
}
