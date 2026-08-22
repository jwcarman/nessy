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

/**
 * The durable wiring's answer to a deferral (durable-deliveries spec §3, §5, §6): create carries
 * the continuation — the return address is durable before any dispatch, so the register-after-
 * create window is unexpressible. Every deferral suspends; the eventual completion arrives through
 * the delivery worker, never here.
 *
 * <p>{@code invocation} arrives from the gate ({@link
 * org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor}) already carrying the committed {@code
 * ModelResponseId} — no provisional stand-in. {@code timeout} stamps {@code deadlineAt = now +
 * timeout} into the computation; a tool with no declared timeout waits indefinitely. {@code
 * retrySemantics} rides the continuation itself (via {@link ScopeRouting}) rather than the
 * computation document, so the reaper can decide bump-or-fail straight from the return address it
 * already reads, with no registry lookup.
 */
public final class ComputationDeferredToolCallPolicy implements DeferredToolCallPolicy {

  private final DurableComputationBackend backend;
  private final ObjectMapper mapper;

  public ComputationDeferredToolCallPolicy(DurableComputationBackend backend, ObjectMapper mapper) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public ToolExecution onDeferred(
      ToolCall call,
      CallAddress address,
      ToolInvocationId invocation,
      RetrySemantics retrySemantics,
      Optional<Duration> timeout) {
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
    backend.create(id, invocation, continuation, deadline);
    return new ToolExecution.Deferred(id);
  }
}
