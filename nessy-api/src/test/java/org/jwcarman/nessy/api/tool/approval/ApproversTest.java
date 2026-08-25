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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;

class ApproversTest {

  private static final Approval APPROVED = new Approval.Approved(Optional.empty());

  private static ApprovalRequest request() {
    return ApprovalRequest.draft(
            "ops",
            "a1",
            new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode()),
            new ObjectMapper())
        .freeze();
  }

  /** A context whose defer() parks nothing durable — it just mints an id and counts. */
  static final class FakeContext implements ApprovalContext {
    final AtomicInteger defers = new AtomicInteger();
    private final ApprovalRequest request = request();
    private ApprovalOutcome deferred;

    @Override
    public ApprovalRequest request() {
      return request;
    }

    @Override
    public ApprovalOutcome defer() {
      if (deferred == null) {
        defers.incrementAndGet();
        deferred = new ApprovalOutcome.Deferred(ComputationId.of("fake-" + defers.get()));
      }
      return deferred;
    }
  }

  @Nested
  class TheStatics {

    @Test
    void allowAnswersApprovedWithoutReadingTheRequest() {
      assertThat(Approvers.allow().approve(new FakeContext()))
          .isEqualTo(new ApprovalOutcome.Answered(APPROVED));
    }

    @Test
    void denyAnswersDeniedWithTheReason() {
      assertThat(Approvers.deny("nope").approve(new FakeContext()))
          .isEqualTo(new ApprovalOutcome.Answered(new Approval.Denied("nope", Optional.empty())));
    }

    @Test
    void deferParksThroughTheContext() {
      var context = new FakeContext();

      ApprovalOutcome outcome = Approvers.defer().approve(context);

      assertThat(outcome).isInstanceOf(ApprovalOutcome.Deferred.class);
      assertThat(context.defers).hasValue(1);
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

      assertThat(gate.approve(new FakeContext())).isEqualTo(new ApprovalOutcome.Answered(APPROVED));
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

      ApprovalOutcome outcome = gate.approve(new FakeContext());

      assertThat(outcome)
          .isEqualTo(new ApprovalOutcome.Answered(new Approval.Denied("first", Optional.empty())));
      assertThat(consulted).hasValue(0);
    }

    @Test
    void aMemberThatDefersIsAProgrammingError() {
      Approver gate = Approvers.allOf(Approvers.allow(), Approvers.defer());
      var context = new FakeContext();

      assertThatThrownBy(() -> gate.approve(context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("allOf");
      assertThat(context.defers).hasValue(0);
    }

    @Test
    void anEmptyGateIsRefused() {
      assertThatThrownBy(Approvers::allOf).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
