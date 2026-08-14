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
package org.jwcarman.nessy.examples.hello;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.testing.ScriptedModelProvider;

/**
 * The root README's five-minute example, made runnable: {@link ScriptedModelProvider} plays back a
 * scripted conversation, so this main needs no key, no network, and no Docker to print a real
 * answer from a real harness.
 */
public final class Hello {

  private Hello() {}

  public static void main(String[] args) {
    IO.println(run());
  }

  /**
   * The whole example, factored out so {@code HelloTest} can assert on exactly the line {@link
   * #main} prints.
   */
  static String run() {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("left", 2);
    args.put("right", 2);

    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUse("c1", "add", args)
            .endWithToolUse()
            .text("The answer is 4.")
            .endTurn()
            .build();

    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .model("fake-model")
            .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
            .build();

    StringBuilder text = new StringBuilder();
    RunOutcome outcome =
        agent
            .converse()
            .tell(
                "what is 2+2?",
                TurnObserver.builder().onTextDelta(delta -> text.append(delta.text())).build());

    return text + " (" + outcome.state().status() + ")";
  }

  record Add(int left, int right) {}

  static final class AddTool implements Tool<Add> {

    @Override
    public String name() {
      return "add";
    }

    @Override
    public String description() {
      return "Adds two integers";
    }

    @Override
    public Class<Add> inputType() {
      return Add.class;
    }

    @Override
    public Awaited<ToolResult> execute(Add input, ToolContext context) {
      return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
  }
}
