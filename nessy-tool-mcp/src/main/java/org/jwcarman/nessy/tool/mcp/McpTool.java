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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One tool a remote MCP server advertised, as this engine sees a tool.
 *
 * <p><b>The schema is the server's, verbatim.</b> A generated schema would describe a Java type
 * this module does not have; the server already said exactly what it accepts, so {@link
 * #inputSchema(InputSchemaGenerator)} ignores the generator and hands the server's document through
 * as text. Arguments arrive bound to a {@link JsonNode} for the same reason -- there is no type to
 * bind to that the server did not define -- and go back out as the plain map the SDK wants.
 */
final class McpTool implements Tool<JsonNode> {

  private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

  private final McpSchema.Tool tool;
  private final McpSyncClient client;
  private final JsonMapper mapper;

  McpTool(McpSchema.Tool tool, McpSyncClient client, JsonMapper mapper) {
    this.tool = tool;
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  public ToolName name() {
    return new ToolName(tool.name());
  }

  /** A server may omit the description; a tool may not, so absent becomes empty here. */
  @Override
  public String description() {
    return tool.description() == null ? "" : tool.description();
  }

  @Override
  public Class<JsonNode> inputType() {
    return JsonNode.class;
  }

  @Override
  public InputSchema inputSchema(InputSchemaGenerator generator) {
    return new InputSchema(mapper.writeValueAsString(tool.inputSchema()));
  }

  /**
   * A transport or protocol failure that keeps the call from completing at all propagates uncaught:
   * it is not the server saying "that failed", it is the call never having been made, and the
   * engine's own handling of a throwing tool is what turns that into an outcome.
   */
  @Override
  public Awaited<ToolResult> call(ToolCallRequest<JsonNode> request) {
    Map<String, Object> arguments = mapper.convertValue(request.input(), ARGUMENTS);
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest(tool.name(), arguments));
    return Awaited.ready(toToolResult(result));
  }

  /** The server's own error flag decides which shape this is; the text is the same either way. */
  private ToolResult toToolResult(McpSchema.CallToolResult result) {
    String text = render(result.content());
    return Boolean.TRUE.equals(result.isError())
        ? new ToolResult.Failure(text)
        : ToolResult.ok(new Block.Text(text));
  }

  /**
   * Text content joins with newlines; anything else degrades to its JSON rather than being dropped,
   * so an image or a resource the model cannot see is at least something it can read about.
   */
  private String render(List<McpSchema.Content> content) {
    StringBuilder text = new StringBuilder();
    for (McpSchema.Content item : content) {
      if (!text.isEmpty()) {
        text.append('\n');
      }
      text.append(
          item instanceof McpSchema.TextContent textContent
              ? textContent.text()
              : mapper.writeValueAsString(item));
    }
    return text.toString();
  }
}
