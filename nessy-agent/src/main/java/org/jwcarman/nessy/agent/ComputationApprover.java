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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.approval.Approver;

/**
 * The computation-backed adjudicator (durable-deliveries spec §3, §5, §5a; computation-identity
 * spec §4 addendum): create carries the continuation, so the return address is durable before the
 * call ever suspends. There is no terminal residue to read back here (ruling 6, reversed) — every
 * ask suspends; the decision, whatever it is, arrives through the delivery worker, never through a
 * second read of this computation.
 *
 * <p>{@code request.id()} is already the approval's own deterministic {@link ComputationId} — the
 * caller (the gate) derived it before ever building the {@link ApprovalRequest}, since {@link
 * CallAddress} no longer travels on the request (the whittle ruling). {@code request.responseId()}
 * — the one addition beyond the request's display pair ({@code agentType}/{@code agentId}) — is
 * what still lets this class build a resumable {@code ToolInvocationId} and continuation: there is
 * no other channel back to the committed model response once the full address stops crossing this
 * SPI boundary.
 *
 * <p>Two redrives land differently, and both are absorbed without a duplicate notification. WHILE
 * STILL PENDING: create-then-suspend is idempotent (submit-once) — a re-driven ask re-registers the
 * same continuation at the same deterministic id and never re-notifies, because {@code create} on
 * an already-present document is a CAS conflict, not a create. AFTER A GRANT: the grant arm (spec
 * §5a) never re-enters this class at all — it dispatches the call directly from the grant's own
 * continuation, past the gate. A STALENESS redrive landing after the grant but before its delivery
 * drains is absorbed at the gate itself — {@link
 * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}'s {@link
 * ComputationDeferredToolCallPolicy#pendingComputation} checks both the computation kinds and the
 * deterministic delivery key (computation-identity spec §4) before this approver, or the tool, ever
 * runs again.
 */
public final class ComputationApprover implements Approver {

  private final SubstrateComputations backend;
  private final Consumer<ApprovalRequest> notifier;
  private final ObjectMapper mapper;

  public ComputationApprover(
      SubstrateComputations backend, Consumer<ApprovalRequest> notifier, ObjectMapper mapper) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public Adjudication adjudicate(ApprovalRequest request) {
    ComputationId computation = request.id();
    ToolInvocationId invocation = new ToolInvocationId(request.responseId(), request.call().id());
    Continuation continuation =
        ScopeRouting.continuationFor(
            mapper, request.agentType(), request.agentId(), request.responseId(), request.call());
    CreateResult created = backend.create(computation, invocation, continuation, Optional.empty());
    if (created.created()) {
      notifier.accept(request);
    }
    return new Adjudication.Suspended(computation);
  }
}
