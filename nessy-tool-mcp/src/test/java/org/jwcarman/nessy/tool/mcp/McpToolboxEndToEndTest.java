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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.testing.ScriptedModelProvider;

/**
 * The thesis, proven rather than asserted: an MCP tool, granted to a real {@link Agent} through the
 * real {@link ToolGrant}/{@link UsagePolicy} door, executes through the real {@code
 * GatedToolCallExecutor} into a real in-process MCP server and back — the kernel never learns the
 * difference between this and a hand-written {@link Tool}.
 */
class McpToolboxEndToEndTest {

  private static final Map<String, Object> ECHO_SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of("message", Map.of("type", "string")),
          "required",
          List.of("message"));

  @Test
  void a_granted_mcp_tool_runs_a_real_agent_turn_through_a_real_in_process_mcp_server() {
    McpSchema.Tool echoTool =
        McpSchema.Tool.builder("echo", ECHO_SCHEMA).description("Echoes the message back").build();

    try (McpTestServer fixture =
        McpTestServer.open(
            echoTool,
            (exchange, request) ->
                McpSchema.CallToolResult.builder()
                    .addTextContent("echo: " + request.arguments().get("message"))
                    .build())) {
      Tool<JsonNode> echo = fixture.tool("echo");

      ObjectNode arguments = JsonNodeFactory.instance.objectNode();
      arguments.put("message", "hi there");

      ScriptedModelProvider provider =
          ScriptedModelProvider.builder()
              .toolUse("c1", "echo", arguments)
              .endWithToolUse()
              .text("Done.")
              .endTurn()
              .build();

      Agent<String> agent =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("mcp-consumer")
                          .model("fake-model")
                          .tools(ToolGrant.grant(echo, UsagePolicy.allow())));
      Conversation<String> conversation = agent.converse();

      RunOutcome outcome = conversation.tell("please echo hi there");

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      ToolResultBlock resultBlock =
          (ToolResultBlock)
              agent
                  .contextFor(conversation.conversationId())
                  .messages()
                  .get(2)
                  .content()
                  .getFirst();
      assertThat(resultBlock.isError()).isFalse();
      assertThat(resultBlock.content()).isEqualTo("echo: hi there");
    }
  }
}
