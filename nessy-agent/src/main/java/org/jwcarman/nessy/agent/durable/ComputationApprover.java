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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.CreateResult;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.approval.Approver;

/**
 * The computation-backed adjudicator (durable-deliveries spec §3, §5, §5a): create carries the
 * continuation, so the return address is durable before the call ever suspends. There is no
 * terminal residue to read back here (ruling 6, reversed) — every ask suspends; the decision,
 * whatever it is, arrives through the delivery worker, never through a second read of this
 * computation.
 *
 * <p>Two redrives land differently, and both are absorbed without a duplicate notification. WHILE
 * STILL PENDING: create-then-suspend is idempotent (submit-once) — a re-driven ask re-registers the
 * same continuation at the same deterministic id and never re-notifies, because {@code create} on
 * an already-present document is a CAS conflict, not a create. AFTER A GRANT: the grant arm (spec
 * §5a) never re-enters this class at all — it dispatches the call directly from the grant's own
 * continuation, past the gate. The one remaining exposure was a STALENESS redrive landing after the
 * grant, when this approval id has already been transferred to its outbox delivery and consumed
 * (presence-means-pending leaves no residue to read here) — that redrive used to reach this class,
 * find absence, and treat it as a fresh ask, re-creating and re-notifying. It no longer reaches
 * here: {@link org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}'s gate checks {@link
 * org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy#isPending} on the TOOL computation id first
 * (a grant that ran to a durable tool leaves that id present) and absorbs there, before this
 * approver — or the tool — ever runs again.
 *
 * <p>{@code invocation}'s {@code responseId} component is the real, committed {@code
 * ModelResponseId} — read off the address the gate stamps before the approval is ever asked
 * (durable-deliveries spec §2), not a provisional stand-in.
 */
public final class ComputationApprover implements Approver {

  private final DurableComputationBackend backend;
  private final Consumer<ApprovalRequest> notifier;
  private final ObjectMapper mapper;

  public ComputationApprover(
      DurableComputationBackend backend, Consumer<ApprovalRequest> notifier, ObjectMapper mapper) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public Adjudication adjudicate(ApprovalRequest request) {
    var address = request.address();
    ComputationId computation = address.approval();
    ToolInvocationId invocation = new ToolInvocationId(address.responseId(), request.call().id());
    Continuation continuation =
        ScopeRouting.continuationFor(
            mapper, address.agentType(), address.agentId(), address.responseId(), request.call());
    CreateResult created = backend.create(computation, invocation, continuation, Optional.empty());
    if (created.created()) {
      notifier.accept(request);
    }
    return new Adjudication.Suspended(computation);
  }
}
