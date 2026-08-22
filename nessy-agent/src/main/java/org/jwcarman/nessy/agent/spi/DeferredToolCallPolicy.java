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

import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * What a wiring does when a tool defers (spec §4.3). {@link ToolExecution.Deferred} means the call
 * is suspended into its durable computation: the executor delivers nothing and narrates nothing —
 * deferral is invisible, but the suspension carries its reference. {@link ToolExecution.Immediate}
 * means an outcome to deliver now — the loud in-band failure of a non-durable wiring, or a durable
 * computation's already-terminal answer.
 */
@FunctionalInterface
public interface DeferredToolCallPolicy {

  ToolExecution onDeferred(ToolCall call, CallAddress address);
}
