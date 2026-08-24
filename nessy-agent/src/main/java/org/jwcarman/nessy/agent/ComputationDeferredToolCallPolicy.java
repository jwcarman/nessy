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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.Computation;
import org.jwcarman.nessy.agent.DispatchEntry.DispatchKind;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tool kind's own Continuum wiring (continuum-adoption spec §3, §5): {@code create} carries the
 * continuation, so the return address is durable before the call ever suspends — the tool kind's
 * own {@link ContinuumClient} mints the computation's real identity, which this class records in
 * the {@link DispatchIndex} immediately afterward (create-then-index, spec §5, never the reverse).
 * Every deferral suspends; the eventual completion arrives through {@link DeliveryWorker}'s tool
 * consumer, never through a second read of this computation.
 *
 * <p>{@link #pendingComputation} answers from {@code index} alone (continuum-adoption spec §5) — a
 * single read, kind-agnostic by construction since the index records whichever kind — approval or
 * tool — currently owns the call. It is the ownership-split absorption door (spec §5a, §6): {@link
 * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}'s gate calls it before running the tool,
 * assembling enrichers, or asking the policy at all, on EVERY dispatch (fresh or redriven) — a
 * staleness redrive that reaches a call whose approval is still pending, whose tool computation
 * already exists, or whose grant/completion has already folded, absorbs there, silently.
 *
 * <p>{@link #onDeferred} always writes a TOOL entry — including over a call whose entry currently
 * names an APPROVAL computation (a granted approval whose tool then defers): the entry means "the
 * computation this call is currently in flight under," and the index intentionally overwrites
 * rather than merges (spec §5). This closes spec §11.3 gap 2 — an orphaned approval's grant can no
 * longer find a stale APPROVAL entry still in place once the real grant's tool has deferred.
 */
public final class ComputationDeferredToolCallPolicy implements DeferredToolCallPolicy {

  private static final Logger log =
      LoggerFactory.getLogger(ComputationDeferredToolCallPolicy.class);

  private final DispatchIndex index;
  private final ContinuumClient<ToolResult, Routing> client;

  /**
   * @param index the dispatch index this call's entry is recorded in
   * @param client the tool kind's Continuum client
   */
  public ComputationDeferredToolCallPolicy(
      DispatchIndex index, ContinuumClient<ToolResult, Routing> client) {
    this.index = Objects.requireNonNull(index, "index must not be null");
    this.client = Objects.requireNonNull(client, "client must not be null");
  }

  @Override
  public Optional<ComputationId> pendingComputation(CallAddress address) {
    return index.find(address).map(entry -> ComputationId.of(entry.computationId()));
  }

  @Override
  public ToolExecution onDeferred(ToolCall call, CallAddress address, Optional<Duration> timeout) {
    var routing = new Routing(address.agentType(), address.agentId(), address.responseId(), call);
    Computation created =
        timeout.map(t -> client.create(routing, t)).orElseGet(() -> client.create(routing));
    try {
      index.record(address, new DispatchEntry(created.id().value().toString(), DispatchKind.TOOL));
    } catch (RuntimeException e) {
      // spec §11.5: client.create above already succeeded, so this computation is now orphaned —
      // record failing here must not be silent, or every redrive re-asks the human forever.
      log.error("DispatchIndex.record failed in ComputationDeferredToolCallPolicy.onDeferred", e);
      throw e;
    }
    return new ToolExecution.Deferred(ComputationId.of(created.id().value().toString()));
  }
}
