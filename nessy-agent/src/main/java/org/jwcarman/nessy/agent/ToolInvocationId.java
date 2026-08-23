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

/**
 * The logical identity of one tool invocation (durable-deliveries spec §2): a model response paired
 * with the provider's call id inside that response. Stable across every redispatch and replay —
 * unlike a bare provider {@code ToolCall.id()}, which is not contractually unique over an agent's
 * lifetime, pairing it with the {@code responseId} that minted it closes that hole. Pure strings,
 * zero dependencies.
 *
 * <p>Lives in {@code nessy-agent}, public (computation-identity spec §4 addendum): {@code
 * ToolContext}'s last public forcer is gone — it now carries only the opaque execution {@link
 * org.jwcarman.nessy.api.tool.ComputationId} — so this type no longer needs {@code nessy-api}. It
 * still needs public visibility here because the durable-wiring SPI ({@link
 * org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy#onDeferred}, {@link
 * org.jwcarman.nessy.agent.spi.ToolCallExecutor#executeGrantedToolNow}), both in the cross-package
 * {@code agent.spi}, still carry it across the package line.
 */
public record ToolInvocationId(String responseId, String callId) {

  public ToolInvocationId {
    if (responseId == null || responseId.isBlank()) {
      throw new IllegalArgumentException("responseId must not be blank");
    }
    if (callId == null || callId.isBlank()) {
      throw new IllegalArgumentException("callId must not be blank");
    }
  }
}
