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
package org.jwcarman.nessy.examples;

import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.console.ConsoleRepl;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.model.env.EnvModelProviders;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * The one main {@code AnthropicChat} and {@code OpenAiChat} collapsed into (design §5): {@link
 * EnvModelProviders#fromEnv()} is the provider lesson now — "switch providers by switching the key"
 * — strictly better teaching than two parallel mains that differed only in which provider module
 * they imported. {@link DemoAgent} still supplies the one shared agent definition (tools, grants,
 * the fact-channel listener); {@link ConsoleRepl} supplies the loop, the default renderer, and the
 * spinner this module used to hand-roll three times over across the family.
 */
public final class Chat {

  private static final String ANTHROPIC_MODEL = "claude-haiku-4-5-20251001";
  private static final String OPENAI_MODEL = "gpt-4o-mini";

  private Chat() {}

  /** Picks the provider from the environment, then hands the console to {@code ConsoleRepl}. */
  public static void main(String[] args) {
    ModelProvider provider;
    try {
      provider = EnvModelProviders.fromEnv();
    } catch (IllegalStateException e) {
      IO.println(e.getMessage());
      System.exit(1);
      return;
    }
    boolean anthropic = provider instanceof AnthropicModelProvider;
    String model = anthropic ? ANTHROPIC_MODEL : OPENAI_MODEL;
    Agent<String> agent = DemoAgent.agentFor(provider, model);

    ConsoleRepl.of(agent)
        .banner(
            "Nessy demo ("
                + (anthropic ? "Anthropic" : "OpenAI")
                + ", "
                + model
                + "). Type exit or quit to leave.")
        .prompt("you> ")
        .run();
  }
}
