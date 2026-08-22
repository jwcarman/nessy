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
import java.util.Objects;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.Outcome;

/**
 * A poke, not a payload (durable-deliveries spec §5): on fire, the scope re-dispatches its
 * outstanding {@code ExecuteTool} effects unconditionally — staleness does not gate a decided
 * approval. Any decision the outcome carries is ignored here; only the scope coordinate matters,
 * which is why this decodes any continuation whose data carries {@code agentType}/{@code agentId},
 * including the richer {@code SCOPE_RESUME} shape the delivery worker reads for completions — the
 * extra {@code call} field is simply unused. The delivery worker reuses this exact mechanism for an
 * approval grant: the tool has not run yet, so there is no {@code ToolFinished} to fold — only a
 * re-fire of the still-outstanding call.
 */
public final class ScopeRedrive {

  public static final String TYPE = "REDRIVE_SCOPE";

  private final AgentResolver resolver;
  private final ObjectMapper mapper;

  public ScopeRedrive(AgentResolver resolver, ObjectMapper mapper) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

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
