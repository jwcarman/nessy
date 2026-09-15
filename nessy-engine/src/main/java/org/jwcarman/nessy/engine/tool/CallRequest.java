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
