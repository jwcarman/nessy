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
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.ToolInvocationId;

/**
 * The durable wiring's answer to a deferral (durable-deliveries spec §3, §5): create carries the
 * continuation — the return address is durable before any dispatch, so the register-after-create
 * window is unexpressible. Every deferral suspends; the eventual completion arrives through the
 * delivery worker, never here.
 *
 * <p>{@code invocation}'s {@code responseId} component is provisional: the committed {@code
 * ModelResponseId} is not reachable at this seam without threading it through {@link CallAddress}
 * and the tool call executor, which durable-deliveries Task 2 leaves untouched (Task 3's
 * territory). The deterministic {@link ComputationId} string stands in for it instead — stable
 * across redispatch, which is what {@link ToolInvocationId} identity requires for this task's
 * scope.
 */
public final class ComputationDeferredToolCallPolicy implements DeferredToolCallPolicy {

  private final DurableComputationBackend backend;
  private final ObjectMapper mapper;

  public ComputationDeferredToolCallPolicy(DurableComputationBackend backend, ObjectMapper mapper) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public ToolExecution onDeferred(ToolCall call, CallAddress address) {
    ComputationId id = address.execution();
    ToolInvocationId invocation = new ToolInvocationId(id.value(), call.id());
    Continuation continuation =
        ScopeRouting.continuationFor(mapper, address.agentType(), address.agentId(), call);
    backend.create(id, invocation, continuation, Optional.empty());
    return new ToolExecution.Deferred(id);
  }
}
