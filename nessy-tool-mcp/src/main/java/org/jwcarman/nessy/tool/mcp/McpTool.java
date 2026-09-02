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
package org.jwcarman.nessy.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * One MCP server tool, wearing a nessy {@link Tool} face.
 *
 * <p>Arguments pass through untyped — {@link #inputType()} is {@link JsonNode}, exactly as the
 * server's own schema described them, so nothing here derives a record the server never promised to
 * honor. {@link #execute} is a single {@code tools/call} round trip: request/response, never a park
 * — the durable elicitation pairing is a later generation (design §6).
 *
 * <p>Package-private on purpose: the only supported way to get one is {@link
 * McpToolbox#tool(String)} or {@link McpToolbox#tools()}, so a granted MCP tool always came from a
 * live, initialized session.
 *
 * <p>MCP progress notifications are not forwarded to {@link ToolCallRequest#progress} in v1: the
 * SDK's sync client offers only a session-global progress consumer, not one scoped to a single
 * {@code tools/call}, so wiring one here would leak another call's progress into this tool's
 * context.
 */
final class McpTool implements Tool<JsonNode> {

  private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {};

  private final McpSchema.Tool tool;
  private final McpSyncClient client;
  private final ObjectMapper mapper;

  McpTool(McpSchema.Tool tool, McpSyncClient client, ObjectMapper mapper) {
    this.tool = tool;
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  public String name() {
    return tool.name();
  }

  /**
   * The server's description, verbatim — {@code null} becomes {@code ""} since {@link Tool}
   * requires non-null.
   */
  @Override
  public String description() {
    String description = tool.description();
    return description == null ? "" : description;
  }

  @Override
  public Class<JsonNode> inputType() {
    return JsonNode.class;
  }

  /**
   * The server's advertised {@code inputSchema}, never one derived from a record — this is the
   * whole reason an MCP tool cannot go through {@code Schemas.of}: only the server knows the shape
   * it will honor.
   */
  @Override
  public ObjectNode inputSchema() {
    return mapper.valueToTree(tool.inputSchema());
  }

  @Override
  public Awaited<ToolResult> execute(ToolCallRequest<JsonNode> call) {
    JsonNode input = call.input();
    Map<String, Object> arguments = mapper.convertValue(input, ARGUMENTS_TYPE);
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(name(), arguments);
    // A transport/protocol failure that keeps the call from completing at all propagates as a
    // RuntimeException here, uncaught: the engine's own fail-closed handling turns it into an
    // error ToolResult without this tool having to know that.
    McpSchema.CallToolResult result = client.callTool(request);
    return Awaited.ready(toToolResult(result));
  }

  private ToolResult toToolResult(McpSchema.CallToolResult result) {
    String text = renderContent(result.content());
    return Boolean.TRUE.equals(result.isError()) ? ToolResult.error(text) : ToolResult.ok(text);
  }

  /**
   * Text content blocks join with newlines. Non-text content (images, embedded resources) has no
   * text-shaped nessy analog yet: v1 degrades honestly by JSON-encoding the content object into the
   * output rather than dropping it — a documented v1 limitation, tools-only and text-first.
   */
  private String renderContent(List<McpSchema.Content> content) {
    StringBuilder text = new StringBuilder();
    for (McpSchema.Content item : content) {
      if (!text.isEmpty()) {
        text.append('\n');
      }
      if (item instanceof McpSchema.TextContent textContent) {
        text.append(textContent.text());
      } else {
        text.append(mapper.valueToTree(item).toString());
      }
    }
    return text.toString();
  }
}
