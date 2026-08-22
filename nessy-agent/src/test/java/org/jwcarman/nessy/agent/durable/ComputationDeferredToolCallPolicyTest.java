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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.PendingComputation;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class ComputationDeferredToolCallPolicyTest {

  private final SubstrateComputations backend =
      new SubstrateComputations(new InMemorySubstrate(), TestMappers.plainlyPinned());
  private final ComputationDeferredToolCallPolicy policy =
      new ComputationDeferredToolCallPolicy(backend, TestMappers.plainlyPinned());

  private static final ToolCall CALL =
      new ToolCall("c1", "restart_prod", JsonNodeFactory.instance.objectNode());
  private static final CallAddress ADDRESS = new CallAddress("approver", "demo", "r1", "c1");
  private static final ToolInvocationId INVOCATION = new ToolInvocationId("r1", "c1");
  private static final ComputationId COMPUTATION = ComputationId.of("tool:approver:demo:r1:c1");

  @Test
  void aFirstDeferralCreatesTheComputationAndSuspends() {
    assertThat(
            policy.onDeferred(
                CALL, ADDRESS, INVOCATION, RetrySemantics.NON_RETRYABLE, Optional.empty()))
        .isEqualTo(new ToolExecution.Deferred(COMPUTATION));
    assertThat(backend.find(COMPUTATION)).isPresent();
  }

  @Test
  void aReDriveFindsTheExistingComputationAndStaysSuspended() {
    policy.onDeferred(CALL, ADDRESS, INVOCATION, RetrySemantics.NON_RETRYABLE, Optional.empty());

    assertThat(
            policy.onDeferred(
                CALL, ADDRESS, INVOCATION, RetrySemantics.NON_RETRYABLE, Optional.empty()))
        .isEqualTo(new ToolExecution.Deferred(COMPUTATION));

    PendingComputation pending = backend.find(COMPUTATION).orElseThrow();
    assertThat(pending.returnAddress().type()).isEqualTo("SCOPE_RESUME");
  }

  @Test
  void theReturnAddressCarriesTheAgentCoordinateAndTheCall() {
    policy.onDeferred(CALL, ADDRESS, INVOCATION, RetrySemantics.NON_RETRYABLE, Optional.empty());

    PendingComputation pending = backend.find(COMPUTATION).orElseThrow();
    ScopeRouting.Routing routing =
        ScopeRouting.decode(TestMappers.plainlyPinned(), pending.returnAddress());
    assertThat(routing.agentType()).isEqualTo(ADDRESS.agentType());
    assertThat(routing.agentId()).isEqualTo(ADDRESS.agentId());
    assertThat(routing.call()).isEqualTo(CALL);
    assertThat(pending.invocation().callId()).isEqualTo("c1");
    assertThat(pending.invocation().responseId()).isEqualTo("r1");
  }

  @Test
  void theRetrySemanticsRidesTheReturnAddress() {
    policy.onDeferred(CALL, ADDRESS, INVOCATION, RetrySemantics.RETRYABLE, Optional.empty());

    PendingComputation pending = backend.find(COMPUTATION).orElseThrow();
    ScopeRouting.Routing routing =
        ScopeRouting.decode(TestMappers.plainlyPinned(), pending.returnAddress());
    assertThat(routing.retrySemantics()).isEqualTo(RetrySemantics.RETRYABLE);
  }

  @Test
  void aDeclaredTimeoutStampsADeadlineAtDispatch() {
    Instant before = Instant.now();

    policy.onDeferred(
        CALL, ADDRESS, INVOCATION, RetrySemantics.RETRYABLE, Optional.of(Duration.ofMinutes(5)));

    PendingComputation pending = backend.find(COMPUTATION).orElseThrow();
    assertThat(pending.deadline()).isPresent();
    assertThat(pending.deadline().orElseThrow()).isAfter(before.plus(Duration.ofMinutes(4)));
  }

  @Test
  void noDeclaredTimeoutLeavesTheComputationDeadlineLess() {
    policy.onDeferred(CALL, ADDRESS, INVOCATION, RetrySemantics.NON_RETRYABLE, Optional.empty());

    PendingComputation pending = backend.find(COMPUTATION).orElseThrow();
    assertThat(pending.deadline()).isEmpty();
  }
}
