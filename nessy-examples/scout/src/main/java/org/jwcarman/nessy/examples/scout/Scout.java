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
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.console.ConsoleApprover;
import org.jwcarman.nessy.console.ConsoleRepl;
import org.jwcarman.nessy.model.env.EnvModelProviders;
import org.jwcarman.nessy.model.env.EnvModelProviders.Selection;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.plan.PlanStore;
import org.jwcarman.nessy.spi.plan.PlanTools;
import org.jwcarman.nessy.spi.transcript.Transcript;
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

  private static final String DEEPWIKI_URL = "https://mcp.deepwiki.com/mcp";

  static final String SYSTEM_PROMPT =
      "You are Scout, a research assistant that answers questions about public GitHub "
          + "repositories using DeepWiki's documentation tools. Read the wiki structure first, "
          + "then its contents, before asking a follow-up question of the repository itself. "
          + "DeepWiki lookups are exact-match on owner/repo: always copy repository names "
          + "character-for-character from the user's message, and if a lookup reports the "
          + "repository unindexed, re-check your spelling against the user's message before "
          + "concluding it is missing. Cite what you found. Be concise. For multi-step "
          + "research, maintain a task list with update_plan.";

  private Scout() {}

  public static void main(String[] args) {
    Selection selection;
    try {
      selection = EnvModelProviders.select();
    } catch (IllegalStateException e) {
      IO.println(e.getMessage());
      System.exit(1);
      return;
    }
    Harness harness = Nessy.harness(selection.provider()).build();
    ObjectMapper mapper = new ObjectMapper();

    try (McpToolbox toolbox =
        McpToolbox.connect(
            HttpClientStreamableHttpTransport.builder(DEEPWIKI_URL).build(), mapper)) {
      Built built = scout(harness, toolbox, selection.model(), new ConsoleApprover());

      ConsoleRepl.of(built.agent())
          .banner(
              "Scout ("
                  + selection.providerName()
                  + ", "
                  + selection.model()
                  + "), reading via DeepWiki. Type exit or quit to leave.")
          .prompt("you> ")
          .plan(built.planStore())
          .run();
    }
  }

  /**
   * The construction seam both {@link #main} and the test share: the demo's real grant table, built
   * from whatever {@link McpToolbox} it is handed — a live DeepWiki session at runtime, an
   * in-process test server in {@code ScoutTest} — so the test exercises the exact wiring the demo
   * runs, not a parallel copy of it. The approver is a parameter, not baked in: {@link #main}
   * always hands it a {@link ConsoleApprover}, but the test needs a non-blocking double, and the
   * grant table — the thing under test — is identical either way.
   *
   * <p>Returns the {@link PlanStore} alongside the agent (rather than the agent alone) so {@link
   * #main} can hand the same store to {@code ConsoleRepl.Builder#plan(PlanStore)} — the grant
   * principle applied to the console's own opt-in: the store the model writes through {@code
   * update_plan} is the exact store the REPL reads back to render the checklist.
   */
  static Built scout(Harness harness, McpToolbox toolbox, String model, Approver approver) {
    PlanStore planStore = PlanStore.inMemory();
    Transcript transcript = Transcript.inMemory();
    Agent<String> agent =
        harness
            .agent()
            .name("scout")
            .model(model)
            .systemPrompt(SYSTEM_PROMPT)
            .tools(
                ToolGrant.grant(toolbox.tool("read_wiki_structure"), UsagePolicy.allow()),
                ToolGrant.grant(toolbox.tool("read_wiki_contents"), UsagePolicy.allow()),
                ToolGrant.grant(toolbox.tool("ask_question"), UsagePolicy.requireApproval()),
                ToolGrant.grant(PlanTools.updatePlan(planStore), UsagePolicy.allow()))
            .memory(Memory.pipeline(transcript).transform(PlanTools.transformer(planStore)).build())
            .approver(approver)
            .build();
    return new Built(agent, planStore);
  }

  /** The agent {@link #scout} builds, paired with the {@link PlanStore} it writes its plan into. */
  record Built(Agent<String> agent, PlanStore planStore) {}
}
