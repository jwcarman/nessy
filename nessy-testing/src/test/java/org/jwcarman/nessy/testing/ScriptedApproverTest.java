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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCall;

class ScriptedApproverTest {

  /** These approvers are asked in isolation, so nothing ever answers at this address. */
  private static final ToolCall CALL =
      new ToolCall(CallId.of("c1"), "restart", JsonNodeFactory.instance.objectNode());

  private static ApprovalRequest asking(String description) {
    return new ApprovalRequest(
        AgentType.of("ops"),
        AgentId.of("prod-eu"),
        TurnId.of("turn-1"),
        CALL.id(),
        CALL.name(),
        CALL.arguments(),
        description,
        Instant.EPOCH,
        () -> new ReplyToken("nowhere"));
  }

  @Test
  void answers_in_the_scripted_order_then_defers() {
    ScriptedApprover approver =
        ScriptedApprover.answering(ApprovalResult.approved(), ApprovalResult.denied("no"));

    Awaited<ApprovalResult> first = approver.approve(asking("first"));
    Awaited<ApprovalResult> second = approver.approve(asking("second"));
    Awaited<ApprovalResult> third = approver.approve(asking("third"));

    assertThat(first).isEqualTo(Awaited.ready(ApprovalResult.approved()));
    assertThat(second).isEqualTo(Awaited.ready(ApprovalResult.denied("no")));
    assertThat(third).isInstanceOf(Awaited.Deferred.class);
  }

  @Test
  void an_empty_script_defers_immediately() {
    ScriptedApprover approver = ScriptedApprover.deferring();

    Awaited<ApprovalResult> result = approver.approve(asking("only"));

    assertThat(result).isInstanceOf(Awaited.Deferred.class);
  }

  @Test
  void a_deferral_leases_a_time_in_the_future() {
    ScriptedApprover approver = ScriptedApprover.deferring();

    Awaited<ApprovalResult> result = approver.approve(asking("only"));

    assertThat(result)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(Awaited.Deferred.class))
        .satisfies(deferred -> assertThat(deferred.expiresAt()).isAfter(Instant.now()));
  }

  @Test
  void records_every_request_it_was_handed_in_order() {
    ScriptedApprover approver =
        ScriptedApprover.answering(ApprovalResult.approved(), ApprovalResult.approved());

    approver.approve(asking("first"));
    approver.approve(asking("second"));

    assertThat(approver.requests())
        .extracting(ApprovalRequest::action)
        .containsExactly("first", "second");
  }

  @Test
  void requests_is_a_snapshot_rather_than_a_live_view() {
    ScriptedApprover approver =
        ScriptedApprover.answering(ApprovalResult.approved(), ApprovalResult.approved());
    approver.approve(asking("first"));
    List<ApprovalRequest> snapshot = approver.requests();

    approver.approve(asking("second"));

    assertThat(snapshot).extracting(ApprovalRequest::action).containsExactly("first");
  }
}
