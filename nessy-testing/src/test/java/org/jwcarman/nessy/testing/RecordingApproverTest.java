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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolCallRequest;

class RecordingApproverTest {

  /** These approvers are asked in isolation, so nothing ever answers at this address. */
  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

  private static ApprovalRequest asking(String description) {
    return new ApprovalRequest(
        new ToolCallRequest(
            AgentType.of("ops"),
            AgentId.of("prod-eu"),
            "turn-1",
            CALL.id(),
            CALL.name(),
            CALL.arguments(),
            new ReplyToken("nowhere")),
        description,
        Instant.EPOCH);
  }

  @Test
  void records_the_request_and_the_answer_its_delegate_produced() {
    ScriptedApprover delegate = ScriptedApprover.answering(ApprovalResult.approved());
    RecordingApprover approver = new RecordingApprover(delegate);
    ApprovalRequest request = asking("restart prod-eu");

    Awaited<ApprovalResult> result = approver.approve(request);

    assertThat(approver.answers()).containsExactly(new RecordingApprover.Answer(request, result));
  }

  @Test
  void requests_is_sugar_over_answers() {
    RecordingApprover approver =
        new RecordingApprover(
            ScriptedApprover.answering(ApprovalResult.approved(), ApprovalResult.denied("no")));

    approver.approve(asking("first"));
    approver.approve(asking("second"));

    assertThat(approver.requests())
        .extracting(ApprovalRequest::description)
        .containsExactly("first", "second");
  }

  @Test
  void a_delegate_that_defers_is_recorded_as_having_deferred() {
    RecordingApprover approver = new RecordingApprover(ScriptedApprover.deferring());

    approver.approve(asking("only"));

    assertThat(approver.answers()).isNotEmpty();
    assertThat(approver.answers())
        .allSatisfy(answer -> assertThat(answer.result()).isInstanceOf(Awaited.Deferred.class));
  }
}
