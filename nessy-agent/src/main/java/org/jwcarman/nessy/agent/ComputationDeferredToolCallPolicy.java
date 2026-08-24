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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The durable wiring's answer to a deferral (durable-deliveries spec §3, §5, §5a, §6;
 * computation-identity spec §3, §4): create carries the continuation — the return address is
 * durable before any dispatch, so the register- after-create window is unexpressible. Every
 * deferral suspends; the eventual completion arrives through the delivery worker, never here.
 *
 * <p>{@code executionBackend} ({@code computation/&lt;agentType&gt;}) is where {@link #onDeferred}
 * creates a durable tool computation — unchanged by the continuum-adoption migration (spec §2): the
 * tool kind stays on the old Substrate machinery until a later task. {@link #pendingComputation}
 * answers from {@code index} alone (continuum-adoption spec §5) — a single read replacing the four
 * store queries the pre-migration stub would have needed, kind-agnostic by construction since the
 * index records whichever kind — approval or tool — currently owns the call.
 *
 * <p>{@code invocation} arrives from the gate ({@link
 * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}) already carrying the committed {@code
 * ModelResponseId} — no provisional stand-in. {@code timeout} stamps {@code deadlineAt = now +
 * timeout} into the computation; a tool with no declared timeout waits indefinitely. {@code
 * retrySemantics} rides the continuation itself (via {@link ScopeRouting}) rather than the
 * computation document, so the reaper can decide bump-or-fail straight from the return address it
 * already reads, with no registry lookup.
 *
 * <p>{@code alsoCommit} (spec §5a invariant 5, transfer-then-dispatch) rides the SAME {@link
 * Substrate#batch} as this computation's creation, via {@link #executionBackend}'s package-visible
 * ops seam — {@link SubstrateComputations} is the only implementation there is (durable-dissolves
 * spec §2).
 *
 * <p>{@link #pendingComputation} is the ownership-split absorption door (spec §5a, §6;
 * computation-identity spec §4): {@link org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}'s
 * gate calls it before running the tool, assembling enrichers, or asking the policy at all, on
 * EVERY dispatch (fresh or redriven) — a staleness redrive that reaches a call whose approval is
 * still pending, whose tool computation already exists, OR whose grant/completion has already
 * folded into an undrained outbox delivery under its own deterministic key, absorbs there,
 * silently, before the tool ever runs again, before the policy or its enrichers ever run again, and
 * before the approver is ever asked again. The delivery-key check is what closes the
 * grant-delivery-pending window (durable-deliveries spec §5a honesty amendment, now shut): the OLD
 * gap was a redrive reaching the APPROVER after the grant had already transferred the work into a
 * delivery, finding absence in both computation kinds (presence- means-pending leaves no residue
 * there), and treating it as a fresh ask.
 */
public final class ComputationDeferredToolCallPolicy implements DeferredToolCallPolicy {

  private final DispatchIndex index;
  private final SubstrateComputations executionBackend;
  private final ObjectMapper mapper;

  /**
   * @param index the dispatch index answering {@link #pendingComputation}
   * @param executionBackend the execution-kind computation store {@link #onDeferred} creates into
   * @param mapper the pinned mapper
   */
  public ComputationDeferredToolCallPolicy(
      DispatchIndex index, SubstrateComputations executionBackend, ObjectMapper mapper) {
    this.index = Objects.requireNonNull(index, "index must not be null");
    this.executionBackend =
        Objects.requireNonNull(executionBackend, "executionBackend must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public Optional<ComputationId> pendingComputation(CallAddress address) {
    return index.find(address).map(entry -> ComputationId.of(entry.computationId()));
  }

  @Override
  public ToolExecution onDeferred(
      ToolCall call,
      CallAddress address,
      ToolInvocationId invocation,
      RetrySemantics retrySemantics,
      Optional<Duration> timeout,
      Optional<Substrate.Op> alsoCommit) {
    // Task 4 replaces this: a locally-derived placeholder rather than a Continuum-minted id.
    ComputationId id = ComputationId.of(address.indexKey());
    Continuation continuation =
        ScopeRouting.continuationFor(
            mapper,
            address.agentType(),
            address.agentId(),
            address.responseId(),
            call,
            retrySemantics,
            timeout);
    Optional<Instant> deadline = timeout.map(Instant.now()::plus);
    executionBackend.create(
        id, invocation, continuation, deadline, alsoCommit.map(List::of).orElse(List.of()));
    return new ToolExecution.Deferred(id);
  }
}
