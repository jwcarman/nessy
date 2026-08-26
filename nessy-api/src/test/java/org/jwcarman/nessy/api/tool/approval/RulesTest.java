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
package org.jwcarman.nessy.api.tool.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RulesTest {

  private static final Approval APPROVED = new Approval.Approved(Optional.empty());

  @Test
  void theFirstAnswerWins() {
    Approver ladder = Approvers.rules(Rules.undecided(), Rules.deny("second"), Rules.allow());

    assertThat(ladder.approve(ApproversTest.fakeContext()))
        .isEqualTo(new ApprovalOutcome.Answered(new Approval.Denied("second", Optional.empty())));
  }

  @Test
  void deferAsTheLastWordParks() {
    Approver ladder = Approvers.rules(Rules.undecided(), Rules.defer());

    ApprovalOutcome outcome = ladder.approve(ApproversTest.fakeContext());

    assertThat(outcome).isInstanceOf(ApprovalOutcome.Deferred.class);
  }

  @Test
  void aLadderThatEndsUndecidedDeniesLoudly() {
    Approver ladder = Approvers.rules(Rules.undecided());

    ApprovalOutcome outcome = ladder.approve(ApproversTest.fakeContext());

    assertThat(outcome).isInstanceOf(ApprovalOutcome.Answered.class);
    Approval answer = ((ApprovalOutcome.Answered) outcome).approval();
    assertThat(answer).isInstanceOf(Approval.Denied.class);
    assertThat(((Approval.Denied) answer).reason()).contains("no rule decided");
  }

  @Test
  void aRuleThatThrowsDeniesNamingIt() {
    Rule broken =
        Rule.named(
            "broken",
            request -> {
              throw new IllegalStateException("kaboom");
            });
    Approver ladder = Approvers.rules(broken, Rules.allow());

    ApprovalOutcome outcome = ladder.approve(ApproversTest.fakeContext());

    Approval answer = ((ApprovalOutcome.Answered) outcome).approval();
    assertThat(((Approval.Denied) answer).reason()).contains("broken").contains("kaboom");
  }

  @Test
  void anEmptyLadderIsRefused() {
    assertThatThrownBy(Approvers::rules).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allowIsAnAnswer() {
    assertThat(Rules.allow().judge(ApproversTest.fakeContext().request()))
        .isEqualTo(new Rule.Verdict.Answered(APPROVED));
  }
}
