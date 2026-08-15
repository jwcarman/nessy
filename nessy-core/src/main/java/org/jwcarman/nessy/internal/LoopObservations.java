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
package org.jwcarman.nessy.internal;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.ToolResult;

/** Span names and attribute assembly for the loop's phases. GenAI-semconv attribute keys. */
public final class LoopObservations {

  private LoopObservations() {}

  // Observation names are Nessy's stable metric identity; contextual names follow the
  // (pre-1.0) OTel GenAI agent span conventions (invoke_agent, chat {model}, execute_tool
  // {tool}). Metrics stay stable even as span conventions evolve.

  private static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";

  public static Observation run(ObservationRegistry registry, ConversationId id) {
    return Observation.start("nessy.run", registry)
        .contextualName("invoke_agent")
        .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, "invoke_agent")
        .highCardinalityKeyValue("gen_ai.conversation.id", id.value());
  }

  public static Observation modelCall(ObservationRegistry registry, String model) {
    return Observation.start("nessy.model.call", registry)
        .contextualName("chat " + model)
        .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, "chat")
        .lowCardinalityKeyValue("gen_ai.request.model", model);
  }

  public static void recordUsage(Observation observation, Usage usage) {
    observation
        .highCardinalityKeyValue("gen_ai.usage.input_tokens", Long.toString(usage.inputTokens()))
        .highCardinalityKeyValue("gen_ai.usage.output_tokens", Long.toString(usage.outputTokens()));
  }

  public static Observation toolCall(ObservationRegistry registry, String toolName, String callId) {
    return Observation.start("nessy.tool.call", registry)
        .contextualName("execute_tool " + toolName)
        .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, "execute_tool")
        .lowCardinalityKeyValue("gen_ai.tool.name", toolName)
        .highCardinalityKeyValue("gen_ai.tool.call.id", callId);
  }

  /** Tags a tool-call observation with its resolved outcome, low cardinality by construction. */
  public static void recordOutcome(Observation observation, ToolResult result) {
    observation.lowCardinalityKeyValue(
        "nessy.tool.outcome", result.isError() ? "error" : "success");
  }

  // No semconv concept exists for a human approval gate; this one is ours.
  public static Observation approvalWait(ObservationRegistry registry, String toolName) {
    return Observation.start("nessy.approval.wait", registry)
        .lowCardinalityKeyValue("gen_ai.tool.name", toolName);
  }
}
