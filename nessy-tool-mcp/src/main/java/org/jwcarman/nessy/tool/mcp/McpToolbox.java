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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every tool one MCP server offers, connected once and bound as ordinary {@link Tool}s.
 *
 * <p>Nothing here knows about approval, rendering or timeouts. Those are what a harness attaches
 * when a tool is bound -- {@code config.tool(toolbox.tool("echo"), t -> t.approver(...))} -- and an
 * MCP tool is governed exactly the way a local one is, with no wrapper class in between.
 */
public final class McpToolbox implements AutoCloseable {

  private final McpSyncClient client;
  private final List<Tool<JsonNode>> tools;

  private McpToolbox(McpSyncClient client, List<Tool<JsonNode>> tools) {
    this.client = client;
    this.tools = tools;
  }

  /**
   * Connects, completes the handshake, and lists the tools.
   *
   * @param mapper reads the server's schemas and this engine's bound arguments. The transport has
   *     its own {@code McpJsonMapper} for the wire; this one is for the two documents that cross
   *     between the SDK's world and the engine's.
   */
  public static McpToolbox connect(McpClientTransport transport, JsonMapper mapper) {
    Objects.requireNonNull(transport, "transport must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    McpSyncClient client = McpClient.sync(transport).build();
    try {
      client.initialize();
      List<Tool<JsonNode>> tools =
          client.listTools().tools().stream()
              .<Tool<JsonNode>>map(tool -> new McpTool(tool, client, mapper))
              .toList();
      return new McpToolbox(client, tools);
    } catch (RuntimeException e) {
      // The handshake did not finish: nothing owns this session yet, so this method closes it
      // itself rather than leaking the subprocess and threads a retry would pile up.
      client.close();
      throw e;
    }
  }

  public List<Tool<JsonNode>> tools() {
    return tools;
  }

  /** Fails naming every tool actually on offer, because a typo should not read as "no tools". */
  public Tool<JsonNode> tool(String name) {
    ToolName wanted = new ToolName(name);
    return tools.stream()
        .filter(candidate -> candidate.name().equals(wanted))
        .findFirst()
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "no such MCP tool: "
                        + name
                        + "; available: "
                        + tools.stream()
                            .map(tool -> tool.name().value())
                            .collect(Collectors.joining(", "))));
  }

  @Override
  public void close() {
    client.close();
  }
}
