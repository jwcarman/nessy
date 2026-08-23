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
import java.util.Arrays;
import java.util.Set;
import org.jwcarman.nessy.agent.host.CliAgent;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.model.env.EnvModelProviders;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.testing.ScriptedModel;

/**
 * The root README's five-minute promise, made runnable: one calculator tool, one turn, printed for
 * real. {@code --scripted} swaps in {@link ScriptedModel} so the promise needs no key, no network,
 * and no Docker; without it, {@link EnvModelProviders#select()} picks a real, bound model handle
 * from whichever API key is set in the environment.
 */
public final class Hello {

  private static final String SYSTEM_PROMPT = "You are a helpful assistant with a calculator tool.";
  private static final String QUESTION = "What is 2+2? Use the calculator tool.";

  private Hello() {}

  public static void main(String[] args) {
    System.out.println(run(Arrays.asList(args)));
  }

  /**
   * The whole example, factored out so {@code HelloTest} can assert on exactly the line {@link
   * #main} prints.
   */
  static String run(Iterable<String> args) {
    boolean scripted = contains(args, "--scripted");
    Model model = scripted ? scriptedModel() : EnvModelProviders.select().model();
    ModelSettings settings = new ModelSettings(1024, Set.of(), null);
    Tool<Calculate> calculator =
        Tool.of(
            Calculate.class,
            t ->
                t.description("Adds two integers.")
                    .executes(calc -> String.valueOf(calc.left() + calc.right())));

    try (CliAgent agent =
        Nessy.cli()
            .model(model)
            .systemPrompt(SYSTEM_PROMPT)
            .settings(settings)
            .tools(calculator)
            .build()) {
      String reply = agent.converse(QUESTION);
      return reply + " (COMPLETE)";
    }
  }

  private static ScriptedModel scriptedModel() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("left", 2);
    arguments.put("right", 2);
    return ScriptedModel.script(
        s ->
            s.toolUse("c1", "calculate", arguments)
                .endWithToolUse()
                .text("The answer is 4.")
                .endTurn());
  }

  private static boolean contains(Iterable<String> args, String flag) {
    for (String arg : args) {
      if (flag.equals(arg)) {
        return true;
      }
    }
    return false;
  }
}
