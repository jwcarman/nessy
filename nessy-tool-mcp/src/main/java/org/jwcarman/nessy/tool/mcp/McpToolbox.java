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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * One MCP server's tools, opened as nessy {@link Tool}s.
 *
 * <p>{@link #connect} performs the MCP {@code initialize}/{@code tools/list} handshake once, up
 * front; from then on {@link #tools()} and {@link #tool(String)} are plain in-memory lookups
 * against that snapshot. Two servers make two toolboxes and two namespaces — a name collision
 * between them is the application's business, made visible by the grant list itself, since nothing
 * here pre-authorizes anything: every tool this toolbox opens still needs its own {@link
 * org.jwcarman.nessy.api.tool.ToolBinding}.
 *
 * <p>{@link #close()} closes the underlying client session. A tool obtained before that point keeps
 * working as a plain Java reference, but calling {@link Tool#execute} on it afterward fails loud —
 * the closed session refuses the call, and that failure propagates rather than being swallowed.
 *
 * <p>Thread-safe for the shape this module expects: one toolbox shared across the agents and
 * conversations that were granted its tools. The underlying SDK session correlates every {@code
 * tools/call} by its own unique request id, so concurrent calls through the same {@code
 * McpSyncClient} do not cross-talk — nothing here needs its own locking on top of that.
 */
public final class McpToolbox implements AutoCloseable {

  private final McpSyncClient client;
  private final List<Tool<JsonNode>> tools;

  private McpToolbox(McpSyncClient client, List<Tool<JsonNode>> tools) {
    this.client = client;
    this.tools = tools;
  }

  /**
   * Connects over {@code transport}, initializes the session, and lists the server's tools.
   *
   * <p>{@code transport} arrives already built — stdio, Streamable HTTP, or any other transport the
   * SDK offers, wired with whatever {@code McpJsonMapper} its own construction idiom calls for.
   * Nessy adds no transport of its own.
   *
   * <p>If the handshake itself fails — a dead server, an initialization timeout, a protocol
   * mismatch — the session this method opened is closed before the failure propagates, so a caller
   * retrying {@code connect} never orphans a subprocess, reader thread, or scheduler from the
   * attempt that failed. Ownership of {@code transport} passes to the returned {@link McpToolbox}
   * on success; on failure, this method has already closed it and the caller owns nothing left to
   * clean up.
   *
   * @param transport the SDK's client transport, already connected to a server
   * @param mapper renders each tool's advertised schema as an {@link
   *     com.fasterxml.jackson.databind.node.ObjectNode} and binds a call's {@link JsonNode}
   *     arguments back to the {@link java.util.Map} the SDK's {@code CallToolRequest} expects
   * @return the toolbox, holding every tool the server advertised, each wearing a nessy {@link
   *     Tool} face
   */
  public static McpToolbox connect(McpClientTransport transport, ObjectMapper mapper) {
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
      // The handshake didn't finish: nothing owns this session yet, so this method closes it
      // itself rather than leaking the subprocess/threads a retry would otherwise pile up.
      client.close();
      throw e;
    }
  }

  /** Every tool the server advertised, in the order {@code tools/list} returned them. */
  public List<Tool<JsonNode>> tools() {
    return tools;
  }

  /** One tool by name. Fail-noisy: the message names every tool actually on offer. */
  public Tool<JsonNode> tool(String name) {
    return tools.stream()
        .filter(candidate -> candidate.name().equals(name))
        .findFirst()
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "no such MCP tool: "
                        + name
                        + "; available: "
                        + tools.stream().map(Tool::name).collect(Collectors.joining(", "))));
  }

  /** Closes the underlying client session. Idempotent, per the SDK's own {@code close()}. */
  @Override
  public void close() {
    client.close();
  }
}
