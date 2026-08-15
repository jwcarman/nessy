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
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.util.Objects;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.tool.mcp.McpToolbox;

/**
 * The agent that reads other people's code: a terminal REPL, chat-cli's exact posture, wired
 * against a remote, no-auth MCP server — <a href="https://mcp.deepwiki.com/mcp">DeepWiki</a> — with
 * an imported toolbox granted tool-by-tool. Two of its three tools are ungated ({@code
 * read_wiki_structure}, {@code read_wiki_contents}); the third, {@code ask_question}, routes every
 * call through the console approval gate, the deliberate beat: a human approves a <em>remote</em>
 * server's tool call, reading {@code describe()}'s name-plus-JSON prompt before it runs.
 *
 * <p>The DeepWiki tool names are verified against the live server, not guessed — see the module
 * README for how and when. A drifted name fails loud at {@link McpToolbox#tool(String)}, right at
 * startup, before the REPL ever opens: the covenant of importing someone else's toolbox.
 */
public final class Scout {

  private static final String API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";
  private static final String MODEL = "claude-haiku-4-5-20251001";
  private static final String DEEPWIKI_URL = "https://mcp.deepwiki.com/mcp";

  static final String SYSTEM_PROMPT =
      "You are Scout, a research assistant that answers questions about public GitHub "
          + "repositories using DeepWiki's documentation tools. Read the wiki structure first, "
          + "then its contents, before asking a follow-up question of the repository itself. "
          + "Cite what you found. Be concise.";

  private Scout() {}

  public static void main(String[] args) {
    if (System.getenv(API_KEY_ENV_VAR) == null) {
      IO.println("Set " + API_KEY_ENV_VAR + " to run this example.");
      System.exit(1);
      return;
    }

    AnthropicModelProvider provider = AnthropicModelProvider.builder().fromEnv().build();
    Harness harness = Nessy.harness(provider).build();
    ObjectMapper mapper = new ObjectMapper();

    try (McpToolbox toolbox =
        McpToolbox.connect(
            HttpClientStreamableHttpTransport.builder(DEEPWIKI_URL).build(), mapper)) {
      Agent<String> agent = scout(harness, toolbox, MODEL, new ConsoleApprover());
      Conversation<String> conversation = agent.converse();

      IO.println(
          "Scout (Anthropic, " + MODEL + "), reading via DeepWiki. Empty line or /quit to exit.");
      while (true) {
        String input = IO.readln("you> ");
        // IO.readln returns null at EOF (e.g. Ctrl-D on the console); treat that the same as
        // /quit rather than NPE-ing on the isBlank() check below.
        if (input == null || input.isBlank() || input.equals("/quit")) {
          return;
        }
        RunOutcome outcome = conversation.tell(input, Scout::render);
        IO.println();
        if (outcome.state().status() == ConversationStatus.FAILED) {
          IO.println(
              "! "
                  + Objects.requireNonNullElse(outcome.state().failureReason(), "unknown failure"));
        }
      }
    }
  }

  /**
   * The construction seam both {@link #main} and the test share: the demo's real grant table, built
   * from whatever {@link McpToolbox} it is handed — a live DeepWiki session at runtime, an
   * in-process test server in {@code ScoutTest} — so the test exercises the exact wiring the demo
   * runs, not a parallel copy of it. The approver is a parameter, not baked in: {@link #main}
   * always hands it a {@link ConsoleApprover}, but the test needs a non-blocking double, and the
   * grant table — the thing under test — is identical either way.
   */
  static Agent<String> scout(Harness harness, McpToolbox toolbox, String model, Approver approver) {
    return harness
        .agent()
        .name("scout")
        .model(model)
        .systemPrompt(SYSTEM_PROMPT)
        .tools(
            ToolGrant.grant(toolbox.tool("read_wiki_structure"), UsagePolicy.allow()),
            ToolGrant.grant(toolbox.tool("read_wiki_contents"), UsagePolicy.allow()),
            ToolGrant.grant(toolbox.tool("ask_question"), UsagePolicy.requireApproval()))
        .approver(approver)
        .build();
  }

  private static void render(TurnEvent event) {
    switch (event) {
      case TurnEvent.TextDelta(String text) -> IO.print(text);
      case TurnEvent.ToolCallRequested(ToolCall call) -> IO.println("\n⚙ tool: " + call.name());
      // deliberate extender-tolerance default (unlike SseEvents' exhaustive no-default switch):
      // the CLI just ignores variants it has no console rendering for.
      default -> {}
    }
  }
}
