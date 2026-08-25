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
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The tool kind's own Continuum wiring (continuum-adoption spec §3, §5): {@code create} carries the
 * continuation, so the return address is durable before the call ever suspends — the tool kind's
 * own {@link ContinuumClient} mints the computation's real identity, which the executor hands
 * straight back to the reducer as {@code ToolDeferred}. Every deferral suspends; the eventual
 * completion arrives through {@link DeliveryWorker}'s tool consumer, never through a second read of
 * this computation.
 *
 * <p>No index (approval-lifecycle spec §8): the phase names its computations now — a call whose
 * status is {@code AwaitingResult(id)} is never re-fired, which is the absorption the dispatch
 * index used to provide.
 */
public final class ComputationDeferredToolCallPolicy implements DeferredToolCallPolicy {

  private final ContinuumClient<ToolResult, Routing> client;

  /**
   * @param client the tool kind's Continuum client
   */
  public ComputationDeferredToolCallPolicy(ContinuumClient<ToolResult, Routing> client) {
    this.client = Objects.requireNonNull(client, "client must not be null");
  }

  @Override
  public ToolExecution onDeferred(ToolCall call, CallAddress address, Optional<Duration> timeout) {
    var routing = new Routing(address.agentType(), address.agentId(), address.responseId(), call);
    Computation created =
        timeout.map(t -> client.create(routing, t)).orElseGet(() -> client.create(routing));
    return new ToolExecution.Deferred(ComputationId.of(created.id().value().toString()));
  }
}
