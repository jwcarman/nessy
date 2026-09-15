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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class McpToolboxTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Map<String, Object> ECHO_SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of("message", Map.of("type", "string")),
          "required",
          List.of("message"));

  private static McpSchema.Tool echoTool() {
    return echoTool("Echoes the message back");
  }

  private static McpSchema.Tool echoTool(String description) {
    return McpSchema.Tool.builder("echo", ECHO_SCHEMA).description(description).build();
  }

  private static McpSchema.CallToolResult textResult(String... lines) {
    return McpSchema.CallToolResult.builder().textContent(List.of(lines)).build();
  }

  private static ObjectNode echoArguments(String message) {
    ObjectNode arguments = MAPPER.createObjectNode();
    arguments.put("message", message);
    return arguments;
  }

  /**
   * Where an answer would go if the tool deferred. An MCP {@code tools/call} is a single round trip
   * and never defers, so nothing reads it.
   */
  /** What the engine hands a running tool. No mocking library, and none needed. */
  private static ToolCallRequest<JsonNode> contextFor(JsonNode arguments) {
    return new ToolCallRequest<>() {
      @Override
      public AgentType agentType() {
        return new AgentType("mcp-test");
      }

      @Override
      public AgentId agentId() {
        return new AgentId(UUID.randomUUID());
      }

      @Override
      public TurnId turn() {
        return new TurnId(1);
      }

      @Override
      public CallId callId() {
        return new CallId("call-1");
      }

      @Override
      public ToolName toolName() {
        return new ToolName("echo");
      }

      @Override
      public JsonNode input() {
        return arguments;
      }

      @Override
      public Instant deadline() {
        return Instant.now().plusSeconds(30);
      }

      @Override
      public ReplyToken replyToken() {
        return new ReplyToken("unused-by-a-tool-that-never-defers");
      }
    };
  }

  /**
   * The text of a successful result. ToolResult is sealed now — Success carries content blocks and
   * Failure carries a message — so a test reads one arm or the other rather than a flag and a
   * string that were legal in any combination.
   */
  private static String successText(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Success.class);
    return ((ToolResult.Success) result)
        .blocks().stream()
            .map(block -> ((Block.Text) block).text())
            .collect(Collectors.joining("\n"));
  }

  private static String failureMessage(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Failure.class);
    return ((ToolResult.Failure) result).message();
  }

  private static ToolResult readyResult(Awaited<ToolResult> awaited) {
    if (awaited instanceof Awaited.Ready<ToolResult> ready) {
      return ready.value();
    }
    throw new AssertionError("expected an Awaited.Ready but got: " + awaited);
  }

  @Nested
  class Discovery {

    @Test
    void tools_mirrors_every_tool_the_server_advertised() {
      try (McpTestServer fixture =
          McpTestServer.open(echoTool(), (exchange, request) -> textResult("ok"))) {

        List<Tool<JsonNode>> tools = fixture.toolbox().tools();

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().name()).isEqualTo(new ToolName("echo"));
      }
    }

    @Test
    void tool_lookup_fails_noisy_and_names_every_tool_actually_on_offer() {
      try (McpTestServer fixture =
          McpTestServer.open(echoTool(), (exchange, request) -> textResult("ok"))) {
        McpToolbox toolbox = fixture.toolbox();

        assertThatThrownBy(() -> toolbox.tool("missing"))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining("missing")
            .hasMessageContaining("echo");
      }
    }
  }

  @Nested
  class Schema_fidelity {

    @Test
    void the_served_schema_comes_back_byte_equal_off_the_tool() {
      try (McpTestServer fixture =
          McpTestServer.open(echoTool(), (exchange, request) -> textResult("ok"))) {

        Tool<JsonNode> tool = fixture.tool("echo");

        // The schema crosses as text and is compared as a document: byte order inside a map is
        // the serializer's business, not the contract.
        assertThat(MAPPER.readTree(tool.inputSchema(_ -> null).json()))
            .isEqualTo(MAPPER.valueToTree(ECHO_SCHEMA));
        assertThat(tool.name()).isEqualTo(new ToolName("echo"));
        assertThat(tool.description()).isEqualTo("Echoes the message back");
      }
    }

    @Test
    void a_null_server_description_becomes_an_empty_string_not_a_null() {
      try (McpTestServer fixture =
          McpTestServer.open(echoTool(null), (exchange, request) -> textResult("ok"))) {

        assertThat(fixture.tool("echo").description()).isEmpty();
      }
    }
  }

  @Nested
  class A_failed_handshake {

    @Test
    void connect_closes_the_session_it_opened_before_the_failure_propagates() {
      FailingClientTransport transport = new FailingClientTransport();

      assertThatThrownBy(() -> McpToolbox.connect(transport, MAPPER))
          .isInstanceOf(RuntimeException.class);

      assertThat(transport.wasClosed()).isTrue();
    }
  }

  @Nested
  class Execution {

    @Test
    void arguments_round_trip_to_the_server_call_handler() {
      AtomicReference<Map<String, Object>> received = new AtomicReference<>();
      try (McpTestServer fixture =
          McpTestServer.open(
              echoTool(),
              (exchange, request) -> {
                received.set(request.arguments());
                return textResult("ok");
              })) {
        Tool<JsonNode> tool = fixture.tool("echo");
        JsonNode arguments = echoArguments("hi there");

        tool.call(contextFor(arguments));

        assertThat(received.get()).containsExactly(Map.entry("message", "hi there"));
      }
    }

    @Test
    void text_content_blocks_join_with_newlines_into_a_success_result() {
      try (McpTestServer fixture =
          McpTestServer.open(
              echoTool(), (exchange, request) -> textResult("line one", "line two"))) {
        Tool<JsonNode> tool = fixture.tool("echo");
        JsonNode arguments = echoArguments("hi");

        ToolResult result = readyResult(tool.call(contextFor(arguments)));

        assertThat(successText(result)).isEqualTo("line one\nline two");
      }
    }

    @Test
    void an_error_result_maps_to_the_error_shaped_tool_result() {
      try (McpTestServer fixture =
          McpTestServer.open(
              echoTool(),
              (exchange, request) ->
                  McpSchema.CallToolResult.builder()
                      .addTextContent("boom")
                      .isError(true)
                      .build())) {
        Tool<JsonNode> tool = fixture.tool("echo");
        JsonNode arguments = echoArguments("hi");

        ToolResult result = readyResult(tool.call(contextFor(arguments)));

        assertThat(failureMessage(result)).isEqualTo("boom");
      }
    }

    @Test
    void non_text_content_degrades_to_json_encoded_text_instead_of_being_dropped() {
      try (McpTestServer fixture =
          McpTestServer.open(
              echoTool(),
              (exchange, request) ->
                  McpSchema.CallToolResult.builder()
                      .addContent(McpSchema.ImageContent.builder("YWJj", "image/png").build())
                      .build())) {
        Tool<JsonNode> tool = fixture.tool("echo");
        JsonNode arguments = echoArguments("hi");

        ToolResult result = readyResult(tool.call(contextFor(arguments)));

        assertThat(successText(result)).contains("YWJj").contains("image/png");
      }
    }
  }

  /**
   * Governance -- approver, action renderer, timeout -- is attached where a tool is BOUND, on the
   * harness config, and an MCP tool is bound exactly like a local one. There is no wrapper class to
   * test here because there is no wrapper: the engine's own ToolBinding tests cover the binding,
   * and this module's job ends at producing an ordinary {@link Tool}.
   */
  @Nested
  class Closed_toolbox {

    @Test
    void a_tool_obtained_before_close_fails_loud_when_executed_afterward() {
      McpTestServer fixture =
          McpTestServer.open(echoTool(), (exchange, request) -> textResult("ok"));
      Tool<JsonNode> tool = fixture.tool("echo");
      JsonNode arguments = echoArguments("hi");
      ToolCallRequest<JsonNode> context = contextFor(arguments);
      fixture.close();

      assertThatThrownBy(() -> tool.call(context))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("failed to initialize")
          .rootCause()
          .hasMessageContaining("transport is closed");
    }
  }
}
