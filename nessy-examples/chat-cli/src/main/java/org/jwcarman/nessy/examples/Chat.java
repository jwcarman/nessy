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

import org.jwcarman.nessy.console.ConsoleRepl;
import org.jwcarman.nessy.model.env.EnvModelProviders;
import org.jwcarman.nessy.model.env.EnvModelProviders.Selection;

/**
 * The one main {@code AnthropicChat} and {@code OpenAiChat} collapsed into (design §5): {@link
 * EnvModelProviders#select()} is the provider lesson now — "switch providers by switching the key"
 * — strictly better teaching than two parallel mains that differed only in which provider module
 * they imported. The provider and model names shown in the banner come straight from the {@link
 * Selection} the env helper made, rather than this class re-deriving them via {@code instanceof} —
 * the knowledge of which provider was picked, and which model goes with it, belongs to the
 * selector. {@link DemoAgent} still supplies the one shared agent definition (tools, grants, the
 * fact-channel listener); {@link ConsoleRepl} supplies the loop, the default renderer, and the
 * spinner this module used to hand-roll three times over across the family.
 */
public final class Chat {

  private Chat() {}

  /**
   * Picks the provider and model from the environment, then hands the console to {@code
   * ConsoleRepl}.
   */
  public static void main(String[] args) {
    Selection selection;
    try {
      selection = EnvModelProviders.select();
    } catch (IllegalStateException e) {
      IO.println(e.getMessage());
      System.exit(1);
      return;
    }
    DemoAgent.Built built = DemoAgent.agentFor(selection.provider(), selection.model());

    ConsoleRepl.of(built.agent())
        .banner(
            "Nessy demo ("
                + selection.providerName()
                + ", "
                + selection.model()
                + "). Type exit or quit to leave. Ask for something multi-step to watch it"
                + " plan.")
        .prompt("you> ")
        .plan(built.planStore())
        .farewell("goodbye.")
        .run();
    // The REPL is done, but the model-provider SDK's HTTP client keeps non-daemon worker
    // threads alive after the last call (idle connection pools linger for up to a minute).
    // Exiting here is honest cleanup of SDK threads we don't own, not a workaround for
    // state we failed to release.
    System.exit(0);
  }
}
