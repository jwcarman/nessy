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
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolEvent;
import org.jwcarman.nessy.api.tool.ToolEventListener;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The Continuum-backed door behind {@link ToolContext#defer()} (tool-context-defer spec §1.1, §2):
 * creates the tool computation with this call's routing and the tool's declared timeout, folds
 * {@link AgentEvent.ToolDeferred} through the sink — synchronously, so the phase names the wait
 * before this returns — and hands back the id. Idempotent. The twin of {@link
 * ComputationApprovalContext}.
 *
 * <p>Public because {@code RegistryToolCallExecutor} builds one per call from a different package;
 * wiring, never application vocabulary.
 */
public final class ComputationToolContext implements ToolContext {

  private final ContinuumClient<ToolResult, Routing> client;
  private final Routing routing;
  private final Optional<Duration> timeout;
  private final ToolEventListener events;
  private final Sink sink;
  private ComputationId deferred;

  /**
   * @param client the tool kind's Continuum client
   * @param routing where this call's answer is delivered
   * @param timeout the tool's declared timeout, which becomes the computation's deadline
   * @param events where {@link ToolEvent.Progress} is narrated
   * @param sink where {@code ToolDeferred} is folded
   */
  public ComputationToolContext(
      ContinuumClient<ToolResult, Routing> client,
      Routing routing,
      Optional<Duration> timeout,
      ToolEventListener events,
      Sink sink) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.routing = Objects.requireNonNull(routing, "routing must not be null");
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.sink = Objects.requireNonNull(sink, "sink must not be null");
  }

  @Override
  public ToolCall call() {
    return routing.call();
  }

  @Override
  public ComputationId invocation() {
    return ComputationId.of(
        new CallAddress(
                routing.agentType(), routing.agentId(), routing.responseId(), routing.call().id())
            .digest());
  }

  @Override
  public void progress(String message) {
    events.on(new ToolEvent.Progress(message));
  }

  @Override
  public synchronized ComputationId defer() {
    if (deferred != null) {
      return deferred;
    }
    Computation created =
        timeout.map(t -> client.create(routing, t)).orElseGet(() -> client.create(routing));
    ComputationId id = ComputationId.of(created.id().value().toString());
    // Folds now, on this thread: deliver rethrows if the fold does not commit (spec §3), and then
    // nobody ever holds this id — the orphan computation expires into a dropped mismatch.
    sink.deliver(new AgentEvent.ToolDeferred(routing.call(), id));
    deferred = id;
    return id;
  }

  /**
   * The executor's question, never a tool's: after {@code execute} returns, this is how {@code
   * RegistryToolCallExecutor} learns whether the wait was recorded (spec §8.1) and so whether the
   * tool's {@code Awaited} arm is the legal one. Public only because the executor lives in {@code
   * agent.tool} — a different package — which is the spec's stated fallback for the package-visible
   * shape it would otherwise prefer.
   *
   * <p>{@code synchronized} on the same lock {@link #defer()} holds: a tool is free to defer from a
   * worker thread and return from the executor's, and this must see that write rather than a stale
   * null — which would make the executor mistake a parked call for one that never deferred.
   *
   * @return the id {@link #defer()} minted, or empty if this call never deferred
   */
  public synchronized Optional<ComputationId> deferral() {
    return Optional.ofNullable(deferred);
  }
}
