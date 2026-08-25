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
package org.jwcarman.nessy.agent.spi;

import java.time.Duration;
import java.util.Optional;
import org.jwcarman.nessy.agent.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * What a wiring does when a tool defers (spec §4.3). {@link ToolExecution.Deferred} means the call
 * is suspended into its durable computation: the executor delivers nothing and narrates nothing —
 * deferral is invisible, but the suspension carries its reference. {@link ToolExecution.Immediate}
 * means an outcome to deliver now — the loud in-band failure of a non-durable wiring, or a durable
 * computation's already-terminal answer.
 *
 * <p>{@code timeout} is the tool's registration-time fact (durable-deliveries spec §6) a durable
 * wiring needs to stamp the computation's deadline — straight from the tool's {@code
 * Tool#timeout()}. (continuum-adoption spec §3): retryability is not implemented, and Continuum's
 * own outbox is what acknowledges a completion now, so neither {@code retrySemantics} nor {@code
 * alsoCommit} — the outbox-delete-composition door — survive on this interface; a durable wiring's
 * {@code create} call is what carries the continuation, so the return address is durable before any
 * dispatch, and there is no longer a Nessy-owned outbox delete to compose alongside it.
 */
@FunctionalInterface
public interface DeferredToolCallPolicy {

  ToolExecution onDeferred(ToolCall call, CallAddress address, Optional<Duration> timeout);
}
