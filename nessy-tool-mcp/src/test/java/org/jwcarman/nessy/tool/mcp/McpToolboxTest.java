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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalContext;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolDescriber;
import org.jwcarman.nessy.api.tool.ToolResult;

class McpToolboxTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("message", message);
    return arguments;
  }

  /**
   * Where an answer would go if the tool deferred. An MCP {@code tools/call} is a single round trip
   * and never defers, so nothing reads it.
   */
  private static ToolContext contextFor(JsonNode arguments) {
    return new Call(
        AgentType.of("mcp-test"),
        AgentId.of("one"),
        new ReplyToken("unused-by-a-tool-that-never-defers"));
  }

  /** An MCP tool keeps nothing per agent, but a context is no longer a single method. */
  private record Call(AgentType agentType, AgentId agentId, ReplyToken replyToken)
      implements ToolContext {}

  /**
   * The text of a successful result. ToolResult is sealed now — Success carries content blocks and
   * Failure carries a message — so a test reads one arm or the other rather than a flag and a
   * string that were legal in any combination.
   */
  private static String successText(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Success.class);
    return ((ToolResult.Success) result)
        .content().stream()
            .map(block -> ((org.jwcarman.nessy.api.block.TextBlock) block).text())
            .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static String failureMessage(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Failure.class);
    return ((ToolResult.Failure) result).message();
  }

  private static ToolResult readyResult(Awaited<ToolResult> awaited) {
    if (awaited instanceof Awaited.Ready<ToolResult> ready) {
      return ready.result();
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
        assertThat(tools.getFirst().name()).isEqualTo("echo");
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

        assertThat(tool.inputSchema()).isEqualTo(MAPPER.valueToTree(ECHO_SCHEMA));
        assertThat(tool.name()).isEqualTo("echo");
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

        tool.execute(arguments, contextFor(arguments));

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

        ToolResult result = readyResult(tool.execute(arguments, contextFor(arguments)));

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

        ToolResult result = readyResult(tool.execute(arguments, contextFor(arguments)));

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

        ToolResult result = readyResult(tool.execute(arguments, contextFor(arguments)));

        assertThat(successText(result)).contains("YWJj").contains("image/png");
      }
    }
  }

  @Nested
  class Governance_without_a_wrapper {

    /**
     * Spec §0's claim that a third-party tool is governable with no wrapper class of nessy's own:
     * {@link McpTool} (obtained here through {@link McpToolbox#tool(String)}, package-private and
     * never subclassed by this test) goes straight into a {@link ToolBinding} with a {@link
     * ToolDescriber} that states the call and a pinned denying approver.
     */
    @Test
    void governs_a_fetched_mcp_tool_directly_with_no_wrapper_class() {
      try (McpTestServer fixture =
          McpTestServer.open(echoTool(), (exchange, request) -> textResult("ok"))) {
        Tool<JsonNode> tool = fixture.tool("echo");
        JsonNode arguments = echoArguments("hi there");
        ToolDescriber<JsonNode> describer = args -> tool.name() + " " + args;
        Approver deny = (request, context) -> Awaited.ready(ApprovalResult.denied("pinned"));

        ToolBinding<JsonNode> binding = new ToolBinding<>(tool, deny, describer);

        // The binding is the whole of the governance: the tool it wraps is untouched, the
        // description a human would read comes off the describer, and the answer off the approver.
        assertThat(binding.tool()).isSameAs(tool);
        assertThat(binding.describer().describe(arguments)).isEqualTo("echo " + arguments);
        assertThat(
                binding
                    .approver()
                    .approve(
                        approvalRequestFor(arguments),
                        ((ApprovalContext) () -> new ReplyToken("nowhere"))))
            .isEqualTo(Awaited.ready(ApprovalResult.denied("pinned")));
      }
    }

    private static ApprovalRequest approvalRequestFor(JsonNode arguments) {
      return new ApprovalRequest(
          AgentType.of("test-agent"),
          AgentId.of("scope-1"),
          new ToolCall("call-1", "echo", arguments),
          "echo " + arguments,
          Instant.EPOCH);
    }
  }

  @Nested
  class Closed_toolbox {

    @Test
    void a_tool_obtained_before_close_fails_loud_when_executed_afterward() {
      McpTestServer fixture =
          McpTestServer.open(echoTool(), (exchange, request) -> textResult("ok"));
      Tool<JsonNode> tool = fixture.tool("echo");
      JsonNode arguments = echoArguments("hi");
      ToolContext context = contextFor(arguments);
      fixture.close();

      assertThatThrownBy(() -> tool.execute(arguments, context))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("failed to initialize")
          .rootCause()
          .hasMessageContaining("transport is closed");
    }
  }
}
