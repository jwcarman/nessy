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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;

class ApproversTest {

  private static final Approval APPROVED = new Approval.Approved(Optional.empty());

  private static ApprovalRequest request() {
    return ApprovalRequest.draft(
            "ops",
            "a1",
            new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode()),
            Map.of(),
            new ObjectMapper())
        .freeze();
  }

  /** The whole of a context now: the frozen question, and nothing to call. */
  record FakeContext(ApprovalRequest request) implements ApprovalContext {}

  /** The context every test in this file hands an approver. */
  static FakeContext fakeContext() {
    return new FakeContext(request());
  }

  @Nested
  class TheStatics {

    @Test
    void allowAnswersApprovedWithoutReadingTheRequest() {
      assertThat(Approvers.allow().approve(fakeContext()))
          .isEqualTo(new ApprovalOutcome.Answered(APPROVED));
    }

    @Test
    void denyAnswersDeniedWithTheReason() {
      assertThat(Approvers.deny("nope").approve(fakeContext()))
          .isEqualTo(new ApprovalOutcome.Answered(new Approval.Denied("nope", Optional.empty())));
    }

    @Test
    void defer_returns_a_deferral_carrying_a_callback_and_a_term() {
      ApprovalOutcome outcome = Approvers.defer().approve(fakeContext());

      assertThat(outcome)
          .isInstanceOfSatisfying(
              ApprovalOutcome.Deferred.class,
              deferred -> {
                assertThat(deferred.callback()).isNotNull();
                assertThat(deferred.term()).isPositive();
              });
    }

    @Test
    void defer_takes_the_term_an_approver_names() {
      ApprovalOutcome outcome = Approvers.defer(Duration.ofHours(3)).approve(fakeContext());

      assertThat(outcome)
          .isInstanceOfSatisfying(
              ApprovalOutcome.Deferred.class,
              deferred -> assertThat(deferred.term()).isEqualTo(Duration.ofHours(3)));
    }

    @Test
    void allowAndDenyAreStaticAndDeferIsNot() {
      assertThat(Approvers.allow()).isInstanceOf(Approvers.Static.class);
      assertThat(Approvers.deny("x")).isInstanceOf(Approvers.Static.class);
      assertThat(Approvers.defer()).isNotInstanceOf(Approvers.Static.class);
      assertThat(((Approvers.Static) Approvers.allow()).answer()).isEqualTo(APPROVED);
    }

    @Test
    void aBlankDenialReasonIsRefused() {
      assertThatThrownBy(() -> Approvers.deny(" ")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class AllOf {

    @Test
    void everyMemberApprovingApproves() {
      Approver gate = Approvers.allOf(Approvers.allow(), Approvers.allow());

      assertThat(gate.approve(fakeContext())).isEqualTo(new ApprovalOutcome.Answered(APPROVED));
    }

    @Test
    void theFirstDenialWinsAndLaterMembersAreNotConsulted() {
      var consulted = new AtomicInteger();
      Approver counting =
          context -> {
            consulted.incrementAndGet();
            return new ApprovalOutcome.Answered(APPROVED);
          };
      Approver gate = Approvers.allOf(Approvers.deny("first"), counting);

      ApprovalOutcome outcome = gate.approve(fakeContext());

      assertThat(outcome)
          .isEqualTo(new ApprovalOutcome.Answered(new Approval.Denied("first", Optional.empty())));
      assertThat(consulted).hasValue(0);
    }

    @Test
    void aMemberThatDefersIsAProgrammingError() {
      Approver gate = Approvers.allOf(Approvers.allow(), Approvers.defer());
      var context = fakeContext();

      assertThatThrownBy(() -> gate.approve(context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("allOf");
    }

    @Test
    void anEmptyGateIsRefused() {
      assertThatThrownBy(Approvers::allOf).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
