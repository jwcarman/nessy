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
package org.jwcarman.nessy.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * One call the model asked for, and everything anyone answering it needs.
 *
 * <p>This replaces two separate context objects. A tool was handed a {@code ToolCallRequest} and an
 * approver an {@code ApprovalContext}, and between them they carried the same three or four facts
 * about the same call — so the pair had to be kept in step, and an approver could not see what the
 * tool would be given. One record about one call is the whole of it.
 *
 * @param agentType what kind of agent is calling — the namespace every agent id lives in
 * @param agentId which agent
 * @param turnId the turn this call belongs to
 * @param callId the model's own id for the call, unique within ONE response and no further
 * @param toolName the tool the model named
 * @param arguments the arguments it produced, before binding to the tool's input type
 * @param replyToken where an answer goes if this call is not answered on the spot
 */
public record ToolCallRequest(
    AgentType agentType,
    AgentId agentId,
    String turnId,
    String callId,
    String toolName,
    JsonNode arguments,
    ReplyToken replyToken) {

  public ToolCallRequest {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(turnId, "turnId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(arguments, "arguments must not be null");
    Objects.requireNonNull(replyToken, "replyToken must not be null");
  }

  /**
   * The key a tool can make itself idempotent on.
   *
   * <p>Tool execution is at-least-once: a call whose process died is run again, because nothing
   * recorded that it had finished. A tool that cares can deduplicate on this.
   *
   * <p>It is the TURN and the call together, because a model's call id is unique only within one
   * response — two turns can each produce a "call_1". It is stable across a re-drive, because
   * recovery resumes the same turn and the claimed asking message pins the same call ids.
   */
  public String callKey() {
    return turnId + "/" + callId;
  }
}
