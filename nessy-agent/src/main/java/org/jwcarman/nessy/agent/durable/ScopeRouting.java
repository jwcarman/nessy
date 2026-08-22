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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.Continuation;

/**
 * Internal wiring: the one continuation shape the durable doors register at dispatch time (durable-
 * deliveries spec §5) — the scope coordinate plus the full call, everything the delivery worker
 * needs to resolve where a completed computation's result belongs. Both {@link
 * ComputationDeferredToolCallPolicy} and {@link ComputationApprover} build this same shape; the
 * worker is the one consumer that reads it back.
 */
final class ScopeRouting {

  static final String TYPE = "SCOPE_RESUME";

  private static final String AGENT_TYPE_FIELD = "agentType";
  private static final String AGENT_ID_FIELD = "agentId";
  private static final String CALL_FIELD = "call";
  private static final String CALL_ID_FIELD = "id";
  private static final String CALL_NAME_FIELD = "name";
  private static final String ARGUMENTS_FIELD = "arguments";

  private ScopeRouting() {}

  static Continuation continuationFor(
      ObjectMapper mapper, String agentType, String agentId, ToolCall call) {
    ObjectNode data = mapper.createObjectNode();
    data.put(AGENT_TYPE_FIELD, agentType);
    data.put(AGENT_ID_FIELD, agentId);
    ObjectNode callNode = data.putObject(CALL_FIELD);
    callNode.put(CALL_ID_FIELD, call.id());
    callNode.put(CALL_NAME_FIELD, call.name());
    callNode.set(ARGUMENTS_FIELD, call.arguments());
    return new Continuation(TYPE, data.toString());
  }

  record Routing(String agentType, String agentId, ToolCall call) {}

  static Routing decode(ObjectMapper mapper, Continuation continuation) {
    JsonNode data;
    try {
      data = mapper.readTree(continuation.data());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("undecodable SCOPE_RESUME continuation", e);
    }
    JsonNode callNode = data.get(CALL_FIELD);
    JsonNode agentType = data.get(AGENT_TYPE_FIELD);
    JsonNode agentId = data.get(AGENT_ID_FIELD);
    if (callNode == null
        || agentType == null
        || agentId == null
        || callNode.get(CALL_ID_FIELD) == null
        || callNode.get(CALL_NAME_FIELD) == null
        || callNode.get(ARGUMENTS_FIELD) == null) {
      throw new IllegalStateException(
          "SCOPE_RESUME continuation missing required fields: " + continuation.data());
    }
    var call =
        new ToolCall(
            callNode.get(CALL_ID_FIELD).asText(),
            callNode.get(CALL_NAME_FIELD).asText(),
            callNode.get(ARGUMENTS_FIELD));
    return new Routing(agentType.asText(), agentId.asText(), call);
  }
}
