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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

class RecordingApproverTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

  private static ApprovalRequest requestNamed(String action) {
    ObjectMapper mapper = new ObjectMapper();
    return ApprovalRequest.draft("ops", "prod-eu", CALL, Map.of(), mapper).action(action).freeze();
  }

  @Test
  void records_the_request_and_outcome_its_delegate_produced() {
    ScriptedApprover delegate = ScriptedApprover.answering(Approval.approved());
    RecordingApprover approver = new RecordingApprover(delegate);
    ApprovalRequest request = requestNamed("restart prod-eu");

    ApprovalOutcome outcome = approver.approve(new AnsweringContext(request));

    assertThat(approver.answers()).containsExactly(new RecordingApprover.Answer(request, outcome));
  }

  @Test
  void requests_is_sugar_over_answers() {
    ScriptedApprover delegate =
        ScriptedApprover.answering(Approval.approved(), Approval.denied("no"));
    RecordingApprover approver = new RecordingApprover(delegate);

    approver.approve(new AnsweringContext(requestNamed("first")));
    approver.approve(new AnsweringContext(requestNamed("second")));

    assertThat(approver.requests())
        .extracting(ApprovalRequest::action)
        .containsExactly("first", "second");
  }

  /** The whole of a context now: the frozen question, and nothing to call. */
  private record AnsweringContext(ApprovalRequest request) implements ApprovalContext {}
}
