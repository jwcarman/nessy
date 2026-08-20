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
package org.jwcarman.nessy.agent.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.durable.InMemoryDurableComputationBackend;
import org.jwcarman.nessy.durable.Outcome;

class DurableParkedCallPolicyTest {

  private final InMemoryDurableComputationBackend backend = new InMemoryDurableComputationBackend();
  private final ApprovalDesk desk = new ApprovalDesk(backend, new ContinuationDispatcher());
  private final DurableParkedCallPolicy policy =
      new DurableParkedCallPolicy(backend, desk, AgentType.of("approver"), AgentId.of("demo"));

  private static final ToolCall CALL =
      new ToolCall("c1", "restart_prod", JsonNodeFactory.instance.objectNode());
  private static final ParkToken TOKEN = new ParkToken("tok-1");
  private static final ComputationId SLOT = ComputationId.of("tool:approver:demo:c1");

  @Test
  void aFirstParkCreatesTheSlotRegistersAndSuspends() {
    assertThat(policy.onParked(CALL, TOKEN)).isEmpty();
    assertThat(backend.status(SLOT)).contains(ComputationStatus.PENDING);
    assertThat(backend.continuationsOf(SLOT)).hasSize(1);
  }

  @Test
  void aReDriveFindsTheExistingSlotAndStaysSuspended() {
    policy.onParked(CALL, TOKEN);
    assertThat(policy.onParked(CALL, TOKEN)).isEmpty();
    assertThat(backend.create(SLOT).created()).isFalse();
    assertThat(backend.continuationsOf(SLOT)).hasSize(1);
  }

  @Test
  void anAnswerThatArrivedWhileAwayDeliversNow() {
    backend.create(SLOT);
    backend.complete(SLOT, new Outcome.Success(ToolResult.ok("pre-approved")));
    var outcome = policy.onParked(CALL, TOKEN);
    assertThat(outcome).isPresent();
    assertThat(outcome.get()).isEqualTo(new ToolOutcome.Returned(ToolResult.ok("pre-approved")));
    var someResult = ToolResult.ok("late");
    assertThatThrownBy(() -> desk.approve(TOKEN, someResult))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
