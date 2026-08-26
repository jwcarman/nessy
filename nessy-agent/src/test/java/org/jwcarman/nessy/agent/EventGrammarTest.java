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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

class EventGrammarTest {

  /** Any deadline: these tests are about routing, not about when a wait ends. */
  private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

  private static final ComputationId PARKED = ComputationId.of("parked-1");
  private static final ApprovalRequest REQUEST =
      ApprovalRequest.draft(
              "ops",
              "prod-1",
              new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode()),
              Map.of(),
              new ObjectMapper())
          .freeze();

  private static ToolCall call(String id) {
    return new ToolCall(id, "lookup", JsonNodeFactory.instance.objectNode());
  }

  @Test
  void anObservationCarriesItsRenderedContent() {
    var observed = new AgentEvent.Observed(List.of(new TextBlock("hi")));
    assertThat(observed.content()).containsExactly(new TextBlock("hi"));
  }

  @Test
  void anObservationRejectsNullContent() {
    assertThatThrownBy(() -> new AgentEvent.Observed(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aModelCompletionWrapsExactlyOneOutcome() {
    var responded =
        new ModelOutcome.Responded(
            List.of(new TextBlock("ok")), List.of(), ModelResponseId.of("response-1"));
    assertThat(new AgentEvent.ModelFinished(responded).outcome()).isEqualTo(responded);
  }

  @Test
  void aModelCompletionRejectsANullOutcome() {
    assertThatThrownBy(() -> new AgentEvent.ModelFinished(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aModelFailureCarriesItsReason() {
    assertThat(new ModelOutcome.Failed("overloaded").reason()).isEqualTo("overloaded");
  }

  @Test
  void aToolCompletionCarriesItsCallAndOutcome() {
    var outcome = new ToolOutcome.Returned(ToolResult.ok("42"));
    var finished = new AgentEvent.ToolFinished(call("c1"), Optional.empty(), outcome);
    assertThat(finished.call().id()).isEqualTo("c1");
    assertThat(finished.tool()).isEmpty();
    assertThat(finished.outcome()).isEqualTo(outcome);
  }

  @Test
  void aToolCompletionRejectsANullComputation() {
    var outcome = new ToolOutcome.Returned(ToolResult.ok("42"));
    var call = call("c1");

    assertThatThrownBy(() -> new AgentEvent.ToolFinished(call, null, outcome))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void anApprovalDeferralCarriesTheParkedComputationAndTheQuestion() {
    var deferred = new AgentEvent.ApprovalDeferred(call("c1"), PARKED, REQUEST, DEADLINE);

    assertThat(deferred.approval()).isEqualTo(PARKED);
    assertThat(deferred.request()).isEqualTo(REQUEST);
  }

  @Test
  void anApprovalDeferralRejectsANullRequest() {
    var call = call("c1");

    assertThatThrownBy(() -> new AgentEvent.ApprovalDeferred(call, PARKED, null, DEADLINE))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void anApprovalAnswerCarriesItsAnswerAndWhereItCameFrom() {
    var answered =
        new AgentEvent.ApprovalAnswered(call("c1"), Optional.of(PARKED), Approval.approved());

    assertThat(answered.approval()).contains(PARKED);
    assertThat(answered.answer()).isEqualTo(Approval.approved());
  }

  @Test
  void anApprovalAnswerRejectsANullAnswer() {
    var call = call("c1");

    assertThatThrownBy(() -> new AgentEvent.ApprovalAnswered(call, Optional.of(PARKED), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aToolDeferralCarriesTheParkedComputation() {
    assertThat(new AgentEvent.ToolCallDeferred(call("c1"), PARKED, DEADLINE).tool())
        .isEqualTo(PARKED);
  }

  @Test
  void aToolDeferralRejectsANullComputation() {
    var call = call("c1");

    assertThatThrownBy(() -> new AgentEvent.ToolCallDeferred(call, null, DEADLINE))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aFailedToolOutcomeCarriesAnError() {
    var failed = new ToolOutcome.Failed(new ToolError("timed out"));
    assertThat(failed.error().message()).isEqualTo("timed out");
  }

  @Test
  void aToolErrorRejectsANullMessage() {
    assertThatThrownBy(() -> new ToolError(null)).isInstanceOf(NullPointerException.class);
  }
}
