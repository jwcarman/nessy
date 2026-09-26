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
package org.jwcarman.nessy.engine.tool;

import java.time.Instant;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * What a tool is handed: its arguments, its deadline, and where a late answer goes.
 *
 * <p>The engine's own implementation of the API's interface, so the API surface stays a set of
 * shapes an application reads and the record that satisfies them is not something it can build.
 */
record CallRequest<I>(
    AgentType agentType,
    AgentId agentId,
    TurnId turn,
    CallId callId,
    ToolName toolName,
    I input,
    Instant deadline,
    ReplyToken replyToken)
    implements ToolCallRequest<I> {}
