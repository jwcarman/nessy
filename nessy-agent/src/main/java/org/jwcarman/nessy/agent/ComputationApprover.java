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
import java.util.function.Consumer;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.Computation;
import org.jwcarman.nessy.agent.DispatchEntry.DispatchKind;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.approval.Approver;

/**
 * The Continuum-backed adjudicator (continuum-adoption spec §3, §5, §7): {@code create} carries the
 * continuation, so the return address is durable before the call ever suspends — the approval
 * kind's own {@link ContinuumClient} mints the computation's real identity, which this class
 * records in the {@link DispatchIndex} immediately afterward (create-then-index, spec §5, never the
 * reverse). There is no terminal residue to read back here: every ask suspends; the decision,
 * whatever it is, arrives through {@link DeliveryWorker#drainApprovals}, never through a second
 * read of this computation.
 *
 * <p>{@code responseId} does not travel on {@link ApprovalRequest} (identity spec §6, the
 * continuation audit): the request is a human decision surface, not a routing packet. This class
 * sources it instead from {@code state}, the scope's own {@link AgentStateStore} — the narrowest
 * existing seam, already owned by the harness for this exact scope. The invariant that makes that
 * read sound: an agent never takes a new turn while the previous turn's tool calls are outstanding
 * (staleness re-fires the SAME outstanding effects; a scope suspended on an approval is quiet on
 * purpose, never stale — a turn is never ABANDONED). So at ask time — always reached from inside
 * the gate's handling of the CURRENT turn's calls — the state loaded here is still that same turn's
 * {@link Phase.AwaitingTools}, and its {@code responseId} IS the asking turn's committed response;
 * there is no window in which a second turn could have started and overwritten it first.
 *
 * <p>The read is a helper's problem, not a caller's promise: {@link #committedResponseId()} still
 * throws if it ever finds the scope in {@link Phase.Idle} or {@link Phase.AwaitingModel} — which
 * would mean the invariant above was actually violated — rather than trust the invariant blindly.
 *
 * <p>Two redrives land differently, and both are absorbed without a duplicate notification. WHILE
 * STILL PENDING: {@link DispatchIndex} already names a computation for the call's address, so
 * {@link org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}'s gate absorbs the redrive before
 * this class is ever reached again — the {@code created.created()} notify guard the old Substrate
 * wiring needed is gone; the index is what prevents the re-notify now. AFTER A GRANT: the grant arm
 * never re-enters this class at all — {@link DeliveryWorker#drainApprovals} dispatches the call
 * directly from the grant's own continuation, past the gate.
 */
public final class ComputationApprover implements Approver {

  private final ContinuumClient<Decision, Routing> client;
  private final DispatchIndex index;
  private final AgentStateStore state;
  private final Consumer<ApprovalRequest> notifier;

  /**
   * @param client the approval kind's Continuum client
   * @param index the dispatch index this call's entry is recorded in
   * @param state the scope's own state store, read at ask time for the committed response id
   * @param notifier fires once, point-to-point, the moment an approval computation is first asked
   */
  public ComputationApprover(
      ContinuumClient<Decision, Routing> client,
      DispatchIndex index,
      AgentStateStore state,
      Consumer<ApprovalRequest> notifier) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.index = Objects.requireNonNull(index, "index must not be null");
    this.state = Objects.requireNonNull(state, "state must not be null");
    this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
  }

  @Override
  public Adjudication adjudicate(ApprovalRequest request) {
    String responseId = committedResponseId();
    var address =
        new CallAddress(request.agentType(), request.agentId(), responseId, request.call().id());
    var routing = new Routing(request.agentType(), request.agentId(), responseId, request.call());
    Computation created = client.create(routing);
    index.record(
        address, new DispatchEntry(created.id().value().toString(), DispatchKind.APPROVAL));
    ComputationId mintedId = ComputationId.of(created.id().value().toString());
    // request.id() is a caller-derived placeholder (RegistryToolCallExecutor never sees the real
    // id before this point) — the notified request carries the REAL, Continuum-minted id instead,
    // so a caller that reads it back (Console's approve/deny, say) targets the right computation.
    notifier.accept(
        new ApprovalRequest(
            mintedId, request.call(), request.agentType(), request.agentId(), request.context()));
    return new Adjudication.Suspended(mintedId);
  }

  /**
   * The read site for the no-new-turn invariant documented on the class: at ask time the scope is
   * always still inside {@link Phase.AwaitingTools} for the turn that produced {@code
   * request.call()}, so its {@code responseId} is unambiguously the asking turn's committed
   * response. {@link Phase.Idle}/{@link Phase.AwaitingModel} here would mean that invariant broke —
   * surfaced loudly rather than guessed at.
   */
  private String committedResponseId() {
    return switch (state.load().phase()) {
      case Phase.AwaitingTools awaiting -> awaiting.responseId().value();
      case Phase.Idle _, Phase.AwaitingModel _ ->
          throw new IllegalStateException(
              "adjudicate reached with no turn awaiting tools in state; the no-new-turn invariant"
                  + " was violated");
    };
  }
}
