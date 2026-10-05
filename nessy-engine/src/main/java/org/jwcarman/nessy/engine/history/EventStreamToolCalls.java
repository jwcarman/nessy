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

package org.jwcarman.nessy.engine.history;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.tool.ToolCalls;

/**
 * The call a model asked for, found in the request that asked for it.
 *
 * <p>An effect names a call by identifier and nothing else -- the fold does not carry arguments,
 * and a row in an outbox must not either. So whoever performs one comes back here for what the
 * model actually said, which is behind the reference the request was written with.
 */
public final class EventStreamToolCalls implements ToolCalls {

  private final AgentType agentType;
  private final AgentEvents events;
  private final Payloads payloads;

  public EventStreamToolCalls(AgentEvents events, Payloads payloads, AgentType agentType) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.payloads = Objects.requireNonNull(payloads, "payloads must not be null");
  }

  @Override
  public Optional<ResolvedCall> find(AgentId agentId, Seq requestSeq, CallId callId) {
    return events.readAll(agentType, agentId).stream()
        .filter(AgentEvent.ActionsRequested.class::isInstance)
        .map(AgentEvent.ActionsRequested.class::cast)
        .filter(asked -> asked.seq().equals(requestSeq))
        .findFirst()
        .flatMap(
            asked ->
                RequestedCalls.resolve(
                    payloads.forAgent(agentId), asked, entry -> entry.id().equals(callId)));
  }
}
