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
 * The computation-backed adjudicator (durable-deliveries spec §3, §5): create carries the
 * continuation, so the return address is durable before the call ever suspends. There is no
 * terminal residue to read back here (ruling 6, reversed) — every ask suspends; the decision,
 * whatever it is, arrives through the delivery worker, never through a second read of this
 * computation. Create-then-suspend is idempotent (submit-once): a re-driven ask re-registers the
 * same continuation and never re-notifies — the notifier fires exactly once, on the ask that
 * created the computation — while the computation is pending, that is: once a decision transfers it
 * to its outbox delivery, the id is deterministically re-derivable again, so a redispatch that
 * lands after the decision re-creates the computation and re-notifies rather than reading the
 * decision back. That is the known Task 2 gap the grant-redispatch path runs into (see the fix-
 * round report); this class does not paper over it.
 *
 * <p>{@code invocation}'s {@code responseId} component is provisional; see {@link
 * ComputationDeferredToolCallPolicy}'s javadoc for why, and for the same Task 3 handoff.
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
    ToolInvocationId invocation = new ToolInvocationId(computation.value(), request.call().id());
    Continuation continuation =
        ScopeRouting.continuationFor(
            mapper, address.agentType(), address.agentId(), request.call());
    CreateResult created = backend.create(computation, invocation, continuation, Optional.empty());
    if (created.created()) {
      notifier.accept(request);
    }
    return new Adjudication.Suspended(computation);
  }
}
