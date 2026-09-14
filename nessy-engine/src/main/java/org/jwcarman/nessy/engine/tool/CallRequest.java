package org.jwcarman.nessy.engine.tool;

import java.time.Instant;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;

/**
 * What a tool is handed: its arguments, its deadline, and where a late answer goes.
 *
 * <p>The engine's own implementation of the API's interface, so the API surface stays a set of
 * shapes an application reads and the record that satisfies them is not something it can build.
 */
record CallRequest<I>(I input, Instant deadline, ReplyToken replyToken)
    implements ToolCallRequest<I> {}
