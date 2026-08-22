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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.ContinuationHandler;
import org.jwcarman.nessy.durable.Outcome;

/**
 * The approval continuation (spec §4.3 amendment): a poke, not a payload. On fire, the scope
 * re-dispatches its outstanding effects unconditionally — staleness does not gate a decided
 * approval. The decision itself travels through the gate's own slot read, so the outcome carried by
 * the continuation is ignored here; only the scope coordinate matters.
 */
public final class ScopeRedrive implements ContinuationHandler {

  public static final String TYPE = "REDRIVE_SCOPE";

  private final AgentResolver resolver;
  private final ObjectMapper mapper;

  public ScopeRedrive(AgentResolver resolver, ObjectMapper mapper) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  /**
   * Equal addresses produce equal {@link Continuation}s, so the backend's set dedups
   * re-registration.
   */
  public Continuation continuationFor(CallAddress address) {
    Objects.requireNonNull(address, "address must not be null");
    ObjectNode data = mapper.createObjectNode();
    data.put("agentType", address.agentType());
    data.put("agentId", address.agentId());
    return new Continuation(TYPE, data.toString());
  }

  @Override
  public void completed(Continuation continuation, Outcome outcome) {
    JsonNode data;
    try {
      data = mapper.readTree(continuation.data());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("undecodable REDRIVE_SCOPE continuation", e);
    }
    JsonNode agentType = data.get("agentType");
    JsonNode agentId = data.get("agentId");
    if (agentType == null
        || agentId == null
        || agentType.asText().isBlank()
        || agentId.asText().isBlank()) {
      throw new IllegalStateException(
          "REDRIVE_SCOPE continuation missing required fields: " + continuation.data());
    }
    AgentType type = AgentType.of(agentType.asText());
    AgentId id = AgentId.of(agentId.asText());
    resolver.resolve(type, id).redispatch();
  }
}
