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
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.Continuation;

/**
 * Internal wiring: the one continuation shape the durable doors register at dispatch time (durable-
 * deliveries spec §5, §5a) — the scope coordinate, the committed {@code responseId}, and the full
 * call: everything the delivery worker needs to resolve where a completed computation's result
 * belongs, and everything the grant arm needs to redispatch the call past the gate with its real
 * {@code CallAddress}/{@code ToolInvocationId}, with no fold read and no re-derivation (spec §5a).
 * Both {@link ComputationDeferredToolCallPolicy} and {@link ComputationApprover} build this same
 * shape; the worker is the one consumer that reads it back.
 */
final class ScopeRouting {

  static final String TYPE = "SCOPE_RESUME";

  private static final String AGENT_TYPE_FIELD = "agentType";
  private static final String AGENT_ID_FIELD = "agentId";
  private static final String RESPONSE_ID_FIELD = "responseId";
  private static final String CALL_FIELD = "call";
  private static final String CALL_ID_FIELD = "id";
  private static final String CALL_NAME_FIELD = "name";
  private static final String ARGUMENTS_FIELD = "arguments";
  private static final String RETRY_SEMANTICS_FIELD = "retrySemantics";
  private static final String TIMEOUT_MILLIS_FIELD = "timeoutMillis";

  private ScopeRouting() {}

  /**
   * The approval door's shape: no retry semantics or timeout travels here — an approval computation
   * is never reaped for retry (durable-deliveries spec §6), only for its (absent, by design)
   * deadline.
   */
  static Continuation continuationFor(
      ObjectMapper mapper, String agentType, String agentId, String responseId, ToolCall call) {
    return writeCommon(
        mapper, agentType, agentId, responseId, call, Optional.empty(), Optional.empty());
  }

  /**
   * The durable-tool door's shape: carries {@code retrySemantics} and the declared {@code timeout}
   * alongside the call, so the reaper can decide bump-or-fail and recompute the next deadline
   * straight from the computation's own return address (durable-deliveries spec §6) — no registry
   * lookup, no separately-persisted creation timestamp. {@link ComputationDeferredToolCallPolicy}
   * is the one caller.
   */
  static Continuation continuationFor(
      ObjectMapper mapper,
      String agentType,
      String agentId,
      String responseId,
      ToolCall call,
      RetrySemantics retrySemantics,
      Optional<Duration> timeout) {
    return writeCommon(
        mapper, agentType, agentId, responseId, call, Optional.of(retrySemantics), timeout);
  }

  /**
   * No null sentinels: the approval door's absent {@code retrySemantics} is {@link
   * Optional#empty()}.
   */
  private static Continuation writeCommon(
      ObjectMapper mapper,
      String agentType,
      String agentId,
      String responseId,
      ToolCall call,
      Optional<RetrySemantics> retrySemantics,
      Optional<Duration> timeout) {
    ObjectNode data = mapper.createObjectNode();
    data.put(AGENT_TYPE_FIELD, agentType);
    data.put(AGENT_ID_FIELD, agentId);
    data.put(RESPONSE_ID_FIELD, responseId);
    ObjectNode callNode = data.putObject(CALL_FIELD);
    callNode.put(CALL_ID_FIELD, call.id());
    callNode.put(CALL_NAME_FIELD, call.name());
    callNode.set(ARGUMENTS_FIELD, call.arguments());
    retrySemantics.ifPresent(r -> data.put(RETRY_SEMANTICS_FIELD, r.name()));
    timeout.ifPresent(t -> data.put(TIMEOUT_MILLIS_FIELD, t.toMillis()));
    return new Continuation(TYPE, data.toString());
  }

  record Routing(
      String agentType,
      String agentId,
      String responseId,
      ToolCall call,
      RetrySemantics retrySemantics,
      Optional<Duration> timeout) {}

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
    JsonNode responseId = data.get(RESPONSE_ID_FIELD);
    if (callNode == null
        || agentType == null
        || agentId == null
        || responseId == null
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
    JsonNode retrySemanticsNode = data.get(RETRY_SEMANTICS_FIELD);
    RetrySemantics retrySemantics =
        retrySemanticsNode == null
            ? RetrySemantics.NON_RETRYABLE
            : RetrySemantics.valueOf(retrySemanticsNode.asText());
    JsonNode timeoutNode = data.get(TIMEOUT_MILLIS_FIELD);
    Optional<Duration> timeout =
        timeoutNode == null
            ? Optional.empty()
            : Optional.of(Duration.ofMillis(timeoutNode.asLong()));
    return new Routing(
        agentType.asText(), agentId.asText(), responseId.asText(), call, retrySemantics, timeout);
  }
}
