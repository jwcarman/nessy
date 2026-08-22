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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The durable wiring's answer to a deferral (durable-deliveries spec §3, §5, §5a, §6): create
 * carries the continuation — the return address is durable before any dispatch, so the register-
 * after-create window is unexpressible. Every deferral suspends; the eventual completion arrives
 * through the delivery worker, never here.
 *
 * <p>{@code invocation} arrives from the gate ({@link
 * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}) already carrying the committed {@code
 * ModelResponseId} — no provisional stand-in. {@code timeout} stamps {@code deadlineAt = now +
 * timeout} into the computation; a tool with no declared timeout waits indefinitely. {@code
 * retrySemantics} rides the continuation itself (via {@link ScopeRouting}) rather than the
 * computation document, so the reaper can decide bump-or-fail straight from the return address it
 * already reads, with no registry lookup.
 *
 * <p>{@code alsoCommit} (spec §5a invariant 5, transfer-then-dispatch): when {@link #backend} is
 * actually a {@link SubstrateComputations} — the only implementation that can share a {@link
 * Substrate#batch} with a caller at all — {@code alsoCommit} rides the SAME batch as this
 * computation's creation, via the package-visible ops seam. A foreign {@link
 * DurableComputationBackend} has no {@code Substrate} to batch into, so {@code alsoCommit} is
 * silently dropped for one — that backend's own {@code complete}/{@code create} contract is its
 * obligation to honor, not this class's to enforce.
 *
 * <p>{@link #pendingComputation} is the ownership-split absorption door (spec §5a, §6): {@link
 * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}'s gate calls it before running the tool,
 * assembling enrichers, or asking the policy at all, on EVERY dispatch (fresh or redriven) — a
 * staleness redrive that reaches a call whose approval is still pending, or whose tool computation
 * already exists, absorbs there, silently, before the tool ever runs again, before the policy or
 * its enrichers ever run again, and before the approver is ever asked again. This replaces the
 * re-create-and-re-notify behavior {@link ComputationApprover}'s javadoc used to describe as the
 * known gap: that gap was a redrive reaching the APPROVER after the grant had already transferred
 * the work into a tool computation, reading absence at the (consumed) approval id, and treating it
 * as a fresh ask. The gate-level check now intercepts that redrive on the TOOL id before it ever
 * reaches the approver — and, separately, a redrive landing while the approval is still undecided
 * is intercepted on the APPROVAL id before the policy (which could be non-constant) ever runs a
 * second time.
 */
public final class ComputationDeferredToolCallPolicy implements DeferredToolCallPolicy {

  private final DurableComputationBackend backend;
  private final ObjectMapper mapper;

  public ComputationDeferredToolCallPolicy(DurableComputationBackend backend, ObjectMapper mapper) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public Optional<ComputationId> pendingComputation(CallAddress address) {
    ComputationId approval = address.approval();
    if (backend.find(approval).isPresent()) {
      return Optional.of(approval);
    }
    ComputationId execution = address.execution();
    if (backend.find(execution).isPresent()) {
      return Optional.of(execution);
    }
    return Optional.empty();
  }

  @Override
  public ToolExecution onDeferred(
      ToolCall call,
      CallAddress address,
      ToolInvocationId invocation,
      RetrySemantics retrySemantics,
      Optional<Duration> timeout,
      Optional<Substrate.Op> alsoCommit) {
    ComputationId id = address.execution();
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
    if (alsoCommit.isPresent() && backend instanceof SubstrateComputations substrateBackend) {
      substrateBackend.create(id, invocation, continuation, deadline, List.of(alsoCommit.get()));
    } else {
      backend.create(id, invocation, continuation, deadline);
    }
    return new ToolExecution.Deferred(id);
  }
}
