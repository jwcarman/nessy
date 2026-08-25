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

import java.util.Objects;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.Computation;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * The Continuum-backed door behind {@link ApprovalContext#defer()} (approval-lifecycle spec §1.3):
 * creates the approval computation with this call's routing and the frozen request as its
 * continuation, folds {@link AgentEvent.ApprovalDeferred} through the sink — synchronously, so the
 * phase names the ask before this returns — and hands back the outcome. Idempotent: a second call
 * returns the same outcome, parking nothing new.
 *
 * <p>Public because {@code HarnessConfig} builds one per call from a different package; it is
 * wiring, never application vocabulary.
 */
public final class ComputationApprovalContext implements ApprovalContext {

  private final ContinuumClient<Approval, ApprovalRouting> client;
  private final Routing routing;
  private final ApprovalRequest request;
  private final Sink sink;
  private ApprovalOutcome deferred;

  /**
   * @param client the approval kind's Continuum client
   * @param routing where this call's answer is delivered
   * @param request the frozen question
   * @param sink where {@code ApprovalDeferred} is folded
   */
  public ComputationApprovalContext(
      ContinuumClient<Approval, ApprovalRouting> client,
      Routing routing,
      ApprovalRequest request,
      Sink sink) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.routing = Objects.requireNonNull(routing, "routing must not be null");
    this.request = Objects.requireNonNull(request, "request must not be null");
    this.sink = Objects.requireNonNull(sink, "sink must not be null");
  }

  @Override
  public ApprovalRequest request() {
    return request;
  }

  @Override
  public synchronized ApprovalOutcome defer() {
    if (deferred != null) {
      return deferred;
    }
    Computation created = client.create(new ApprovalRouting(routing, request));
    ComputationId id = ComputationId.of(created.id().value().toString());
    // Folds now, on this thread: nobody can be told about a question the scope has not recorded,
    // because nobody has the id yet (spec §4, the ordering ruling).
    sink.deliver(new AgentEvent.ApprovalDeferred(routing.call(), id, request));
    deferred = new ApprovalOutcome.Deferred(id);
    return deferred;
  }
}
