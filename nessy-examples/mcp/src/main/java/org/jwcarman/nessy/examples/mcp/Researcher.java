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
package org.jwcarman.nessy.examples.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.console.ConsoleApprover;
import org.jwcarman.nessy.console.Repl;
import org.jwcarman.nessy.tool.mcp.McpToolbox;

/**
 * An agent whose tools belong to somebody else.
 *
 * <p>Every other example in this repository writes its own tools. This one writes none: it connects
 * to <a href="https://deepwiki.com">DeepWiki</a>'s public MCP server and grants what that server
 * advertises. The point is that an imported tool is an ordinary {@link Tool} — granted the same
 * way, gated the same way, described the same way — so nothing downstream can tell the difference.
 *
 * <p><b>Why DeepWiki.</b> It needs no credential, which makes it a real server anyone can run this
 * against, and its tools divide neatly into two kinds: reading is free, and asking costs somebody
 * money. That division is what makes the approval here honest rather than decorative.
 *
 * <p><b>What a drifted server does.</b> Tool names are verified against the live server at connect
 * time, not guessed from documentation: {@link McpToolbox#tool(String)} fails loudly if DeepWiki
 * ever renames one of these. A remote toolbox that changed under you breaks the wiring at startup
 * rather than quietly doing the wrong thing mid-turn.
 *
 * <pre>{@code
 * export OPENAI_API_KEY=not-needed
 * export OPENAI_BASE_URL=http://localhost:1234/v1
 * export NESSY_MODEL=<a model your endpoint serves>
 * ./mvnw -q -pl nessy-examples/mcp -am compile exec:java
 * }</pre>
 */
public final class Researcher {

  /** DeepWiki's public server. No key, no account. */
  private static final String DEEPWIKI = "https://mcp.deepwiki.com";

  private static final String SYSTEM_PROMPT =
      """
      You research public GitHub repositories using the DeepWiki tools you have been granted.

      Work in this order: read_wiki_structure to see what a repository's documentation covers,
      then read_wiki_contents to read the parts that matter. Both are free, so look before you
      ask.

      ask_question puts the question to DeepWiki's own model and costs them money, so use it only
      when reading has not answered it — and expect a person to approve that call.

      Name the repository as "owner/name". Say what you found, and say plainly when you did not
      find it.
      """;

  private Researcher() {}

  public static void main(String[] args) {
    ObjectMapper mapper = new ObjectMapper();
    McpClientTransport transport =
        HttpClientStreamableHttpTransport.builder(DEEPWIKI)
            .endpoint("/mcp")
            .jsonMapper(new JacksonMcpJsonMapper(mapper))
            .build();

    // Closed at the end of the conversation: the toolbox owns the connection to the server.
    try (McpToolbox toolbox = McpToolbox.connect(transport, mapper)) {
      Repl.run(
          config ->
              config
                  .banner("nessy mcp — researching with DeepWiki's tools. /exit to leave.")
                  .prompt("> ")
                  .farewell("bye.")
                  .systemPrompt(SYSTEM_PROMPT)
                  .agent(org.jwcarman.nessy.api.AgentType.of("researcher"))
                  // Reading is free, so it is ungated. Nothing here is Nessy's judgement about
                  // DeepWiki; it is this application's judgement about its own bill.
                  .tool(toolbox.tool("read_wiki_structure"))
                  .tool(toolbox.tool("read_wiki_contents"))
                  // The one that spends someone else's model budget, so the one a person answers.
                  .tool(
                      toolbox.tool("ask_question"),
                      binding ->
                          binding
                              .approver(ConsoleApprover.atTheTerminal())
                              .describer(Researcher::asking)));
    }
  }

  /**
   * The sentence a person consents to.
   *
   * <p>An imported tool's input is a {@link JsonNode} — the server declared the schema, not us — so
   * a describer reads the fields it knows and falls back to the whole document rather than
   * guessing. Showing the raw JSON is still better than showing nothing: consenting to a question
   * you cannot read is not consent.
   */
  private static String asking(JsonNode arguments) {
    JsonNode question = arguments.path("question");
    JsonNode repository = arguments.path("repoName");
    if (question.isMissingNode()) {
      return "Ask DeepWiki: " + arguments;
    }
    return repository.isMissingNode()
        ? "Ask DeepWiki: %s".formatted(question.asText())
        : "Ask DeepWiki about %s: %s".formatted(repository.asText(), question.asText());
  }
}
