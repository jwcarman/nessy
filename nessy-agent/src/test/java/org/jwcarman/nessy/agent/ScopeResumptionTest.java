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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.Outcome;

class ScopeResumptionTest {

  private record Delivered(AgentType type, AgentId id, AgentEvent event) {}

  private final List<Delivered> deliveries = new ArrayList<>();
  private final ScopeResumption handler =
      new ScopeResumption((type, id, event) -> deliveries.add(new Delivered(type, id, event)));

  private static final ToolCall CALL =
      new ToolCall("c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("action", "go"));
  private static final Continuation CONTINUATION =
      ScopeResumption.continuationFor(AgentType.of("approver"), AgentId.of("demo"), CALL);

  @Test
  void aSuccessRoundTripsIntoAReturnedToolFinished() {
    handler.completed(CONTINUATION, new Outcome.Success(ToolResult.ok("approved")));
    assertThat(deliveries).hasSize(1);
    var delivered = deliveries.getFirst();
    assertThat(delivered.type()).isEqualTo(AgentType.of("approver"));
    assertThat(delivered.id()).isEqualTo(AgentId.of("demo"));
    assertThat(delivered.event())
        .isEqualTo(
            new AgentEvent.ToolFinished(CALL, new ToolOutcome.Returned(ToolResult.ok("approved"))));
  }

  @Test
  void aFailureArrivesInBand() {
    handler.completed(CONTINUATION, new Outcome.Failure("nope"));
    var finished = (AgentEvent.ToolFinished) deliveries.getFirst().event();
    assertThat(((ToolOutcome.Failed) finished.outcome()).error().message()).isEqualTo("nope");
  }

  @Test
  void aCancellationArrivesInBandAsCancelled() {
    handler.completed(CONTINUATION, new Outcome.Cancelled("expired"));
    var finished = (AgentEvent.ToolFinished) deliveries.getFirst().event();
    assertThat(((ToolOutcome.Failed) finished.outcome()).error().message()).contains("cancelled");
  }

  @Test
  void anUnexpectedPayloadFailsInBandNotLoudly() {
    handler.completed(CONTINUATION, new Outcome.Success("just a string"));
    var finished = (AgentEvent.ToolFinished) deliveries.getFirst().event();
    assertThat(((ToolOutcome.Failed) finished.outcome()).error().message()).contains("unexpected");
  }

  @Test
  void anUndecodableContinuationIsAProgrammingError() {
    var corrupt = new Continuation(ScopeResumption.TYPE, "not json");
    var outcome = new Outcome.Success(ToolResult.ok("x"));
    assertThatThrownBy(() -> handler.completed(corrupt, outcome))
        .isInstanceOf(IllegalStateException.class);
  }
}
