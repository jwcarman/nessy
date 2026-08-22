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
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.ContinuationHandler;
import org.jwcarman.nessy.durable.Outcome;

/**
 * The one continuation agents contribute (durable spec, preamble ruling 2): when the slot flips,
 * bind the scope and deliver the completion as an ordinary {@code ToolFinished}. The continuation
 * data carries the scope coordinate and the full call — everything a fresh node needs.
 */
public final class ScopeResumption implements ContinuationHandler {

  public static final String TYPE = "RESUME_SCOPE";

  private static final String AGENT_TYPE_FIELD = "agentType";
  private static final String AGENT_ID_FIELD = "agentId";
  private static final String ARGUMENTS_FIELD = "arguments";

  private final AgentBinder binder;
  private final ObjectMapper mapper;

  public ScopeResumption(AgentBinder binder, ObjectMapper mapper) {
    this.binder = Objects.requireNonNull(binder, "binder must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  public Continuation continuationFor(CallAddress address, ToolCall call) {
    Objects.requireNonNull(address, "address must not be null");
    Objects.requireNonNull(call, "call must not be null");
    ObjectNode data = mapper.createObjectNode();
    data.put(AGENT_TYPE_FIELD, address.agentType());
    data.put(AGENT_ID_FIELD, address.agentId());
    ObjectNode callNode = data.putObject("call");
    callNode.put("id", call.id());
    callNode.put("name", call.name());
    callNode.set(ARGUMENTS_FIELD, call.arguments());
    return new Continuation(TYPE, data.toString());
  }

  @Override
  public void completed(Continuation continuation, Outcome outcome) {
    JsonNode data;
    try {
      data = mapper.readTree(continuation.data());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("undecodable RESUME_SCOPE continuation", e);
    }
    JsonNode callNode = data.get("call");
    if (callNode == null
        || data.get(AGENT_TYPE_FIELD) == null
        || data.get(AGENT_ID_FIELD) == null
        || callNode.get("id") == null
        || callNode.get("name") == null
        || callNode.get(ARGUMENTS_FIELD) == null) {
      throw new IllegalStateException(
          "RESUME_SCOPE continuation missing required fields: " + continuation.data());
    }
    var call =
        new ToolCall(
            callNode.get("id").asText(),
            callNode.get("name").asText(),
            callNode.get(ARGUMENTS_FIELD));
    binder.deliver(
        AgentType.of(data.get(AGENT_TYPE_FIELD).asText()),
        AgentId.of(data.get(AGENT_ID_FIELD).asText()),
        new AgentEvent.ToolFinished(call, DurableOutcomes.toToolOutcome(outcome)));
  }
}
