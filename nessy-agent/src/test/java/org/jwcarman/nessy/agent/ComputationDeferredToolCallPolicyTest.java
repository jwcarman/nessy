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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class ComputationDeferredToolCallPolicyTest {

  private final InMemorySubstrate substrate = new InMemorySubstrate();

  private final ContinuumClient<ToolResult, Routing> toolClient =
      TestToolClients.client("tool", TestMappers.plainlyPinned());
  private final DispatchIndex index =
      new DispatchIndex(substrate, TestMappers.plainlyPinned(), "dispatch");
  private final ComputationDeferredToolCallPolicy policy =
      new ComputationDeferredToolCallPolicy(index, toolClient);

  private static final ToolCall CALL =
      new ToolCall("c1", "restart_prod", JsonNodeFactory.instance.objectNode());
  private static final CallAddress ADDRESS = new CallAddress("approver", "demo", "r1", "c1");

  @Test
  void aFirstDeferralCreatesTheComputationAndRecordsItInTheIndex() {
    ToolExecution execution = policy.onDeferred(CALL, ADDRESS, Optional.empty());

    assertThat(execution).isInstanceOf(ToolExecution.Deferred.class);
    ComputationId created = ((ToolExecution.Deferred) execution).id();
    assertThat(index.find(ADDRESS))
        .hasValueSatisfying(
            entry -> {
              assertThat(entry.computationId()).isEqualTo(created.value());
              assertThat(entry.kind()).isEqualTo(DispatchEntry.DispatchKind.TOOL);
            });
  }

  @Test
  void aReDriveFindsTheExistingComputationAndStaysSuspended() {
    ToolExecution first = policy.onDeferred(CALL, ADDRESS, Optional.empty());

    Optional<ComputationId> pending = policy.pendingComputation(ADDRESS);

    assertThat(pending).isPresent();
    assertThat(pending.orElseThrow()).isEqualTo(((ToolExecution.Deferred) first).id());
  }

  @Test
  void aDeclaredTimeoutStampsADeadlineOnTheComputation() {
    Instant before = Instant.now();

    ToolExecution execution = policy.onDeferred(CALL, ADDRESS, Optional.of(Duration.ofMinutes(5)));

    ComputationId created = ((ToolExecution.Deferred) execution).id();
    Optional<org.jwcarman.continuum.api.Computation> found =
        toolClient
            .register(
                org.jwcarman.nessy.agent.ContinuumIdsTestAccess.continuumId(created.value()),
                new Routing("approver", "demo", "r1", CALL))
            .computation();
    assertThat(found).isPresent();
    assertThat(found.orElseThrow().deadline()).isAfter(before.plus(Duration.ofMinutes(4)));
  }

  @Test
  void pendingComputationIsEmptyBeforeAnyDeferral() {
    assertThat(policy.pendingComputation(ADDRESS)).isEmpty();
  }

  @Test
  void pendingComputationFindsAnInFlightToolComputation() {
    assertThat(policy.pendingComputation(ADDRESS)).isEmpty();

    ToolExecution execution = policy.onDeferred(CALL, ADDRESS, Optional.empty());

    assertThat(policy.pendingComputation(ADDRESS))
        .contains(((ToolExecution.Deferred) execution).id());
  }

  @Test
  void pendingComputationFindsAPendingApprovalRecordedInTheIndex() {
    assertThat(policy.pendingComputation(ADDRESS)).isEmpty();

    var approvalId = ComputationId.of(ADDRESS.indexKey());
    index.record(
        ADDRESS, new DispatchEntry(approvalId.value(), DispatchEntry.DispatchKind.APPROVAL));

    assertThat(policy.pendingComputation(ADDRESS)).contains(approvalId);
  }
}
