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
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

class ScriptedApproverTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

  private static ApprovalRequest requestNamed(String action) {
    ObjectMapper mapper = new ObjectMapper();
    return ApprovalRequest.draft("ops", "prod-eu", CALL, Map.of(), mapper).action(action).freeze();
  }

  /** Never defers — every test here scripts an answer, so a real deferring door is unneeded. */
  private static final class AnsweringContext implements ApprovalContext {
    private final ApprovalRequest request;

    AnsweringContext(ApprovalRequest request) {
      this.request = Objects.requireNonNull(request, "request must not be null");
    }

    @Override
    public ApprovalRequest request() {
      return request;
    }

    @Override
    public ApprovalOutcome defer() {
      throw new IllegalStateException("this fixture never defers");
    }
  }

  /** Parks unconditionally — the fixture the empty-script and out-of-script cases exercise. */
  private static final class DeferringContext implements ApprovalContext {
    private final ApprovalRequest request;

    DeferringContext(ApprovalRequest request) {
      this.request = Objects.requireNonNull(request, "request must not be null");
    }

    @Override
    public ApprovalRequest request() {
      return request;
    }

    @Override
    public ApprovalOutcome defer() {
      return new ApprovalOutcome.Deferred(ComputationId.of(UUID.randomUUID().toString()));
    }
  }

  @Test
  void answers_in_the_scripted_order_then_defers() {
    ScriptedApprover approver =
        ScriptedApprover.answering(Approval.approved(), Approval.denied("no"));

    ApprovalOutcome first = approver.approve(new AnsweringContext(requestNamed("first")));
    ApprovalOutcome second = approver.approve(new AnsweringContext(requestNamed("second")));
    ApprovalOutcome third = approver.approve(new DeferringContext(requestNamed("third")));

    assertThat(first).isEqualTo(new ApprovalOutcome.Answered(Approval.approved()));
    assertThat(second).isEqualTo(new ApprovalOutcome.Answered(Approval.denied("no")));
    assertThat(third).isInstanceOf(ApprovalOutcome.Deferred.class);
  }

  @Test
  void an_empty_script_defers_immediately() {
    ScriptedApprover approver = ScriptedApprover.deferring();

    ApprovalOutcome outcome = approver.approve(new DeferringContext(requestNamed("only")));

    assertThat(outcome).isInstanceOf(ApprovalOutcome.Deferred.class);
  }

  @Test
  void records_every_request_it_was_handed_in_order() {
    ScriptedApprover approver =
        ScriptedApprover.answering(Approval.approved(), Approval.approved());

    approver.approve(new AnsweringContext(requestNamed("first")));
    approver.approve(new AnsweringContext(requestNamed("second")));

    assertThat(approver.requests())
        .extracting(ApprovalRequest::action)
        .containsExactly("first", "second");
  }

  @Test
  void requests_is_a_snapshot_rather_than_a_live_view() {
    ScriptedApprover approver =
        ScriptedApprover.answering(Approval.approved(), Approval.approved());
    approver.approve(new AnsweringContext(requestNamed("first")));
    var snapshot = approver.requests();

    approver.approve(new AnsweringContext(requestNamed("second")));

    assertThat(snapshot).hasSize(1);
    assertThat(approver.requests()).hasSize(2);
  }
}
