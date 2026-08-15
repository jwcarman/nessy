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
package org.jwcarman.nessy.examples.scout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.jwcarman.nessy.tool.mcp.McpToolbox;

/**
 * A real, in-process MCP server, shaped like DeepWiki's own — the same three tool names Scout
 * grants ({@code read_wiki_structure}, {@code read_wiki_contents}, {@code ask_question}) — plus the
 * {@link McpToolbox} connected to it. {@link ScoutTest} drives the actual SDK handshake ({@code
 * initialize}, {@code tools/list}, {@code tools/call}) over {@link InMemoryMcpTransport}, never a
 * stub, so {@link org.jwcarman.nessy.examples.scout.Scout#scout} exercises the same tool objects a
 * real DeepWiki connection would hand it.
 *
 * <p>Reproduced in spirit, with attribution, from {@code nessy-tool-mcp}'s own {@code
 * McpTestServer} ({@code
 * nessy-tool-mcp/src/test/java/org/jwcarman/nessy/tool/mcp/McpTestServer.java}) — adapted here to
 * register three tools instead of one, since scout's grant table needs all three to construct.
 */
final class DeepWikiTestServer implements AutoCloseable {

  static final String READ_WIKI_STRUCTURE = "read_wiki_structure";
  static final String READ_WIKI_CONTENTS = "read_wiki_contents";
  static final String ASK_QUESTION = "ask_question";

  private final McpSyncServer server;
  private final McpToolbox toolbox;

  private DeepWikiTestServer(McpSyncServer server, McpToolbox toolbox) {
    this.server = server;
    this.toolbox = toolbox;
  }

  static DeepWikiTestServer open(
      BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
          askQuestionHandler) {
    ObjectMapper wireMapper = new ObjectMapper();
    McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(wireMapper);
    InMemoryMcpTransport.Pair pair = InMemoryMcpTransport.open(jsonMapper);

    McpSyncServer server =
        McpServer.sync(pair.server())
            .serverInfo("deepwiki-test-server", "1.0.0")
            .toolCall(
                repoNameTool(READ_WIKI_STRUCTURE, "Get a list of documentation topics"),
                (exchange, request) ->
                    okResult("structure: " + request.arguments().get("repoName")))
            .toolCall(
                repoNameTool(READ_WIKI_CONTENTS, "View full documentation"),
                (exchange, request) -> okResult("contents: " + request.arguments().get("repoName")))
            .toolCall(questionTool(), askQuestionHandler)
            .build();

    McpToolbox toolbox = McpToolbox.connect(pair.client(), new ObjectMapper());
    return new DeepWikiTestServer(server, toolbox);
  }

  McpToolbox toolbox() {
    return toolbox;
  }

  private static McpSchema.Tool repoNameTool(String name, String description) {
    return McpSchema.Tool.builder(name, repoNameSchema()).description(description).build();
  }

  private static McpSchema.Tool questionTool() {
    return McpSchema.Tool.builder(ASK_QUESTION, questionSchema())
        .description("Ask any question about a repository and get an AI-powered answer")
        .build();
  }

  private static Map<String, Object> repoNameSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of("repoName", Map.of("type", "string")),
        "required",
        List.of("repoName"));
  }

  private static Map<String, Object> questionSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "repoName", Map.of("type", "string"),
            "question", Map.of("type", "string")),
        "required",
        List.of("repoName", "question"));
  }

  static McpSchema.CallToolResult okResult(String text) {
    return McpSchema.CallToolResult.builder().addTextContent(text).build();
  }

  @Override
  public void close() {
    toolbox.close();
    server.close();
  }
}
