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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.function.BiFunction;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * A real, in-process MCP server plus the {@link McpToolbox} connected to it — every test in this
 * module's suite drives the actual SDK handshake ({@code initialize}, {@code tools/list}, {@code
 * tools/call}) over {@link InMemoryMcpTransport}, never a stub.
 */
final class McpTestServer implements AutoCloseable {

  private final McpSyncServer server;
  private final McpToolbox toolbox;

  private McpTestServer(McpSyncServer server, McpToolbox toolbox) {
    this.server = server;
    this.toolbox = toolbox;
  }

  static McpTestServer open(
      McpSchema.Tool tool,
      BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
          handler) {
    ObjectMapper wireMapper = new ObjectMapper();
    McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(wireMapper);
    InMemoryMcpTransport.Pair pair = InMemoryMcpTransport.open(jsonMapper);

    McpSyncServer server =
        McpServer.sync(pair.server())
            .serverInfo("mcp-test-server", "1.0.0")
            .toolCall(tool, handler)
            .build();

    McpToolbox toolbox = McpToolbox.connect(pair.client(), new ObjectMapper());
    return new McpTestServer(server, toolbox);
  }

  McpToolbox toolbox() {
    return toolbox;
  }

  Tool<JsonNode> tool(String name) {
    return toolbox.tool(name);
  }

  @Override
  public void close() {
    toolbox.close();
    server.close();
  }
}
